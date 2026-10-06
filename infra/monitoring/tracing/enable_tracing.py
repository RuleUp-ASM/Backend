"""트레이스(X-Ray) 켜기 — 노션 「RuleUp 모니터링」 1절 트레이스, 4절 4단계(병목 구간 확인). 재실행 안전.

Application Signals 는 조직 SCP 가 막아(application-signals:* explicit deny) 쓸 수 없다. 그래서 같은 ADOT 에이전트로
트레이스만 받아 X-Ray 로 보낸다. API → MySQL(JDBC)·Redis(Lettuce)·외부 HTTP(OAuth·Gemini·FCM)·AWS SDK(SQS·S3·Bedrock)
구간이 자동 계측된다. OTel 지표·로그는 끈다(CloudWatch 사용자 지정 지표 과금 — 앱 지표는 CloudWatchMetricsConfig 허용 목록으로만).

하는 일
1) 태스크 역할에 AWSXRayDaemonWriteAccess
2) X-Ray 샘플링 규칙 — 계정 단위라 stg·prod 공통이다.
   ruleup-skip-health(100) /actuator/* 는 기록하지 않음(ALB 헬스체크).
   ruleup-api(200)         */api/* 요청은 전부 기록.
   Default(10000)          그 밖(배치·고아 DB 쿼리·지표 전송·SQS 폴링)은 1%만. 초당 1건+5% 였을 땐 이것들이 트레이스의 98% 였다
                           (배치 87% — 분당 ~30건이 초당 1건 예약분에 전부 들어갔다).
   그룹 ruleup-<env>-api   API 트레이스만 보는 필터(콘솔 트레이스 목록·서비스 맵에서 그룹을 고른다).
3) 서비스가 지금 쓰는 태스크 정의에 OTEL_* env 와 수집기 사이드카(otel-collector)를 더해 새 리비전 등록 → 서비스 전환

전제: 이미지에 에이전트가 들어 있어야 한다(Dockerfile.deploy). 에이전트 없는 이미지에 OTEL_ENABLED=true 를 주면 기동이 실패한다.
되돌리기: python3 enable_tracing.py <env> --disable  (OTEL_ENABLED=false 리비전 — 사이드카는 남아도 무해)
사용: python3 infra/monitoring/tracing/enable_tracing.py prod|stg [--dry-run] [--disable]"""
import boto3, json, sys, copy
R='ap-northeast-2'
env=sys.argv[1]; DRY='--dry-run' in sys.argv; DISABLE='--disable' in sys.argv
assert env in ('prod','stg')
COLLECTOR_IMAGE='public.ecr.aws/aws-observability/aws-otel-collector:v0.50.0'
COLLECTOR_CONFIG='''extensions:
  health_check:
  awsproxy:
    endpoint: 0.0.0.0:2000
receivers:
  otlp:
    protocols:
      http:
        endpoint: 0.0.0.0:4318
processors:
  batch/traces:
    timeout: 1s
    send_batch_size: 50
exporters:
  awsxray:
    region: ap-northeast-2
    # 어떤 API 였는지를 메타데이터가 아니라 주석(annotation)으로 — 트레이스 상세 상단에 보이고 필터·그룹에 쓸 수 있다.
    # X-Ray 가 점을 밑줄로 바꿔 annotation.http_route = "/api/v1/verifications/sync" 처럼 찾는다(경로 변수는 {id} 그대로).
    indexed_attributes: [http.route, http.request.method, http.response.status_code]
service:
  extensions: [health_check, awsproxy]
  pipelines:
    traces:
      receivers: [otlp]
      processors: [batch/traces]
      exporters: [awsxray]
'''
OTEL_ENV={
    'OTEL_ENABLED':'true',
    'OTEL_SERVICE_NAME':f'ruleup-api-{env}',   # X-Ray 는 서비스 이름으로만 거른다 — 같으면 stg·prod 가 한 노드로 섞인다
    'OTEL_RESOURCE_ATTRIBUTES':f'deployment.environment={env},service.namespace=ruleup',
    'OTEL_EXPORTER_OTLP_PROTOCOL':'http/protobuf',
    'OTEL_EXPORTER_OTLP_TRACES_ENDPOINT':'http://localhost:4318/v1/traces',
    'OTEL_PROPAGATORS':'tracecontext,baggage,xray',
    'OTEL_TRACES_SAMPLER':'xray',
    'OTEL_TRACES_SAMPLER_ARG':'endpoint=http://localhost:2000',
    'OTEL_METRICS_EXPORTER':'none',
    'OTEL_LOGS_EXPORTER':'none',
    'OTEL_AWS_APPLICATION_SIGNALS_ENABLED':'false',
    'OTEL_AWS_SERVICE_EVENTS_FUNCTION_INSTRUMENT_ENABLED':'false',
}

ecs=boto3.client('ecs',region_name=R); iam=boto3.client('iam'); xray=boto3.client('xray',region_name=R)
cluster,service=f'ruleup-{env}-cluster',f'ruleup-{env}-api'

if not DRY and not DISABLE:
    iam.attach_role_policy(RoleName=f'ruleup-{env}-ecs-task',PolicyArn='arn:aws:iam::aws:policy/AWSXRayDaemonWriteAccess')
    # 에이전트는 경로가 아니라 전체 URL(http://10.1.1.73:8080/actuator/health)로 맞춰 본다 — '/actuator/*' 는 한 번도 안 걸렸다.
    rule=dict(RuleName='ruleup-skip-health',Priority=100,FixedRate=0.0,ReservoirSize=0,ServiceName='*',ServiceType='*',
              Host='*',HTTPMethod='*',URLPath='*/actuator/*',ResourceARN='*')
    api=dict(RuleName='ruleup-api',Priority=200,FixedRate=1.0,ReservoirSize=10,ServiceName='*',ServiceType='*',
             Host='*',HTTPMethod='*',URLPath='*/api/*',ResourceARN='*')
    names={r['SamplingRule']['RuleName'] for r in xray.get_sampling_rules()['SamplingRuleRecords']}
    for r in (rule,api):
        if r['RuleName'] in names: xray.update_sampling_rule(SamplingRuleUpdate={k:v for k,v in r.items() if k!='ResourceARN'})
        else: xray.create_sampling_rule(SamplingRule={**r,'Version':1})
    xray.update_sampling_rule(SamplingRuleUpdate={'RuleName':'Default','FixedRate':0.01,'ReservoirSize':0})
    group=f'ruleup-{env}-api'
    expr=f'service("ruleup-api-{env}") AND annotation.http_route BEGINSWITH "/api/"'
    if group in {g['GroupName'] for g in xray.get_groups()['Groups']}: xray.update_group(GroupName=group,FilterExpression=expr)
    else: xray.create_group(GroupName=group,FilterExpression=expr)
    print('iam + sampling rules + group ok')

current=ecs.describe_services(cluster=cluster,services=[service])['services'][0]['taskDefinition']
td=ecs.describe_task_definition(taskDefinition=current)['taskDefinition']
print('base', current)
new=copy.deepcopy(td)
api=next(c for c in new['containerDefinitions'] if c['name']=='api')
envs={e['name']:e['value'] for e in api.get('environment',[])}
if DISABLE: envs['OTEL_ENABLED']='false'
else: envs.update(OTEL_ENV)
api['environment']=[{'name':k,'value':v} for k,v in sorted(envs.items())]
if not DISABLE:
    log=copy.deepcopy(api['logConfiguration']); log['options']['awslogs-stream-prefix']='otel'
    sidecar={'name':'otel-collector','image':COLLECTOR_IMAGE,'essential':False,'cpu':0,'memoryReservation':64,
             'command':['--config=env:OTEL_CONFIG'],'environment':[{'name':'OTEL_CONFIG','value':COLLECTOR_CONFIG}],
             'logConfiguration':log}
    new['containerDefinitions']=[c for c in new['containerDefinitions'] if c['name']!='otel-collector']+[sidecar]
    # 수집기가 먼저 떠 있어야 기동 직후 스팬을 잃지 않는다. 수집기가 죽어도(essential=false) API 는 계속 뜬다.
    api['dependsOn']=[d for d in api.get('dependsOn',[]) if d['containerName']!='otel-collector']+[{'containerName':'otel-collector','condition':'START'}]

KEEP=['family','taskRoleArn','executionRoleArn','networkMode','containerDefinitions','volumes','placementConstraints',
      'requiresCompatibilities','cpu','memory','runtimePlatform','ephemeralStorage','pidMode','ipcMode','proxyConfiguration']
reg={k:new[k] for k in KEEP if new.get(k) not in (None,[],{})}
if DRY:
    print(json.dumps([{'name':c['name'],'image':c['image'],'env':sorted(e['name'] for e in c.get('environment',[])),'dependsOn':c.get('dependsOn')}
                      for c in reg['containerDefinitions']],ensure_ascii=False,indent=1))
    sys.exit()
# 재실행 안전 — 바뀐 게 없으면 새 리비전을 만들지 않는다(서비스 재배포 방지).
# AWS 가 돌려주는 env 순서는 등록 순서와 달라서 정렬해 비교한다(정렬 없이 비교했다가 같은 내용으로 재배포한 적이 있다).
shape=lambda cs:sorted((c['name'],c['image'],sorted((e['name'],e['value']) for e in c.get('environment',[])),
                        str(c.get('dependsOn')),str(c.get('command'))) for c in cs)
if shape(reg['containerDefinitions'])==shape(td['containerDefinitions']):
    print('no change'); sys.exit()
arn=ecs.register_task_definition(**reg)['taskDefinition']['taskDefinitionArn']
ecs.update_service(cluster=cluster,service=service,taskDefinition=arn)
print('service ->',arn)
