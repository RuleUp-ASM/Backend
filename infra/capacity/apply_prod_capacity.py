"""prod 사양 조정 — 예상 사용자 100~200명 + 가용성(DB Multi-AZ, Redis 복제본·자동 장애조치). 재실행 안전.
사용자가 직접 실행한다: python3 infra/capacity/apply_prod_capacity.py
근거: 사용자 1명당 하루 100~300 요청 → 200명이면 평균 1 rps 미만, 리마인더 시각 피크 약 15 rps.
  - ECS  : 1 vCPU/2GB → 0.5 vCPU/2GB. 힙 75%→60%(힙 밖 메모리가 약 450MB라 75%면 2GB 한도에 붙는다).
           Hikari 20→15, 자동 확장 최대 6→4 — t4g.small max_connections(약 130) 안에서 배포 겹침(태스크 2배)까지 버틴다.
           0.5 vCPU 기동 시간은 stg 실측 약 100초 — 헬스체크 유예 180초 안이다.
  - RDS  : db.t4g.medium Single-AZ → db.t4g.small Multi-AZ. 저장 공간 50GB 는 줄일 수 없어 유지.
  - Redis: cache.t4g.small 1노드 → cache.t4g.micro 2노드(복제본은 primary 와 다른 AZ) + 자동 장애조치 + Multi-AZ.
  - 경보 : ruleup-prod-rds-connections 400 → 100 (t4g.small 한도에 맞춤).
stg(10명)는 현 사양 그대로 둔다."""
import boto3, time
R='ap-northeast-2'
ecs=boto3.client('ecs',region_name=R); aas=boto3.client('application-autoscaling',region_name=R)
rds=boto3.client('rds',region_name=R); ec=boto3.client('elasticache',region_name=R); cw=boto3.client('cloudwatch',region_name=R)
CLUSTER,SERVICE='ruleup-prod-cluster','ruleup-prod-api'; DB='ruleup-prod-mysql'; RG='ruleup-prod-redis'

# --- ECS
svc=ecs.describe_services(cluster=CLUSTER,services=[SERVICE])['services'][0]
td=ecs.describe_task_definition(taskDefinition=svc['taskDefinition'])['taskDefinition']
env={'JAVA_TOOL_OPTIONS':'-XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError','SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE':'15'}
c=td['containerDefinitions'][0]
cur={e['name']:e['value'] for e in c.get('environment',[])}
if td['cpu']!='512' or td['memory']!='2048' or any(cur.get(k)!=v for k,v in env.items()):
    cur.update(env); c['environment']=[{'name':k,'value':v} for k,v in cur.items()]
    if c.get('cpu'): c['cpu']=512   # 컨테이너 cpu 합은 태스크 cpu 를 넘을 수 없다(현재 1024)
    keep=['family','taskRoleArn','executionRoleArn','networkMode','containerDefinitions','volumes','placementConstraints',
          'requiresCompatibilities','runtimePlatform','ephemeralStorage','pidMode','ipcMode','proxyConfiguration']
    kw={k:td[k] for k in keep if td.get(k) not in (None,[])}
    new=ecs.register_task_definition(cpu='512',memory='2048',**kw)['taskDefinition']['taskDefinitionArn']
    ecs.update_service(cluster=CLUSTER,service=SERVICE,taskDefinition=new)
    print('ecs ->',new)
aas.register_scalable_target(ServiceNamespace='ecs',ResourceId=f'service/{CLUSTER}/{SERVICE}',
    ScalableDimension='ecs:service:DesiredCount',MinCapacity=2,MaxCapacity=4)
print('ecs autoscaling 2~4')

# --- RDS (클래스 변경 + Multi-AZ 전환을 한 번에. 전환 중 1~2분 끊김, 전체 완료까지 수십 분)
db=rds.describe_db_instances(DBInstanceIdentifier=DB)['DBInstances'][0]
if db['DBInstanceClass']!='db.t4g.small' or not db['MultiAZ']:
    # db.t4g.small 은 Performance Insights 를 지원하지 않는다 — 끈다. 느린 쿼리는 slowquery 로그·경보로 본다.
    rds.modify_db_instance(DBInstanceIdentifier=DB,DBInstanceClass='db.t4g.small',MultiAZ=True,
                           EnablePerformanceInsights=False,ApplyImmediately=True)
    print('rds modifying -> db.t4g.small Multi-AZ')

# --- 경보
a=cw.describe_alarms(AlarmNames=['ruleup-prod-rds-connections'])['MetricAlarms'][0]
if a['Threshold']!=100:
    KEYS=['AlarmName','AlarmDescription','ActionsEnabled','OKActions','AlarmActions','InsufficientDataActions','MetricName','Namespace',
          'Statistic','Dimensions','Period','EvaluationPeriods','DatapointsToAlarm','ComparisonOperator','TreatMissingData']
    kw={k:a[k] for k in KEYS if a.get(k) not in (None,[])}; kw['Threshold']=100
    cw.put_metric_alarm(**kw); print('alarm rds-connections -> 100')

# --- Redis (노드 축소 → 복제본 추가 → 자동 장애조치·Multi-AZ). 각 단계는 앞 단계가 끝나야 한다.
def wait_rg():
    # 그룹이 available 이어도 새 복제본 노드는 아직 creating 일 수 있다 — 노드까지 기다린다.
    while True:
        g=ec.describe_replication_groups(ReplicationGroupId=RG)['ReplicationGroups'][0]
        nodes=[ec.describe_cache_clusters(CacheClusterId=m)['CacheClusters'][0]['CacheClusterStatus'] for m in g['MemberClusters']]
        if g['Status']=='available' and all(s=='available' for s in nodes): return
        time.sleep(30)
rg=ec.describe_replication_groups(ReplicationGroupId=RG)['ReplicationGroups'][0]
if rg['CacheNodeType']!='cache.t4g.micro':
    ec.modify_replication_group(ReplicationGroupId=RG,CacheNodeType='cache.t4g.micro',ApplyImmediately=True)
    print('redis -> cache.t4g.micro'); time.sleep(30); wait_rg()
rg=ec.describe_replication_groups(ReplicationGroupId=RG)['ReplicationGroups'][0]
if len(rg['MemberClusters'])<2:
    primary=ec.describe_cache_clusters(CacheClusterId=rg['MemberClusters'][0])['CacheClusters'][0]['PreferredAvailabilityZone']
    other='ap-northeast-2a' if primary=='ap-northeast-2c' else 'ap-northeast-2c'
    ec.increase_replica_count(ReplicationGroupId=RG,ApplyImmediately=True,ReplicaConfiguration=[
        {'NodeGroupId':rg['NodeGroups'][0]['NodeGroupId'],'NewReplicaCount':1,'PreferredAvailabilityZones':[primary,other]}])
    print('redis replica +1 in',other); time.sleep(30); wait_rg()
wait_rg()   # 재실행으로 앞 단계를 건너뛰어도 복제본 노드가 준비될 때까지 기다린다
rg=ec.describe_replication_groups(ReplicationGroupId=RG)['ReplicationGroups'][0]
if rg['AutomaticFailover']!='enabled' or rg['MultiAZ']!='enabled':
    ec.modify_replication_group(ReplicationGroupId=RG,AutomaticFailoverEnabled=True,MultiAZEnabled=True,ApplyImmediately=True)
    print('redis failover + Multi-AZ on'); time.sleep(30); wait_rg()

print('waiting rds...'); rds.get_waiter('db_instance_available').wait(DBInstanceIdentifier=DB,WaiterConfig={'Delay':60,'MaxAttempts':90})
db=rds.describe_db_instances(DBInstanceIdentifier=DB)['DBInstances'][0]
print('rds',db['DBInstanceClass'],'MultiAZ',db['MultiAZ'],'pending',db.get('PendingModifiedValues'))
ecs.get_waiter('services_stable').wait(cluster=CLUSTER,services=[SERVICE]); print('ecs stable. done')
