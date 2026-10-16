"""prod 사양 조정 — DAU 2만 부하테스트(2026-10-16~17, 노션 「부하테스트 및 성능개선」 9절) 결론. 재실행 안전.
사용자가 직접 실행한다(main 머지 **전에**): python3 infra/capacity/apply_prod_dau2.py [--max-inflight 64]

main CD 는 서비스의 현재 태스크 정의를 그대로 두고 이미지만 바꿔 다시 띄운다. 그래서 사양은 여기서 먼저 바꿔 둔다.
  1. ECS 1 vCPU/2GB — 0.5 vCPU 는 피크 전에 CPU 100% 로 막힌다(1차 시험). Hikari 15 는 그대로.
  2. 헬스체크를 관리 포트(8081)로 분리 — 급증 때 요청 스레드 200개가 다 차면 /actuator/health 도 줄을 서 5초를 넘기고,
     ECS 가 멀쩡한 태스크를 연달아 교체했다(2차 급증 시험). 관리 포트는 자기 Tomcat 커넥터를 따로 쓴다.
     끊김 없이 옮기는 순서: ALB→ECS 8081 허용 → 헬스체크 경로를 옛·새 태스크 모두 응답하는 /health/public 으로
     → MANAGEMENT_SERVER_PORT=8081 로 배포 → 헬스체크를 8081 /actuator/health 로. 순서를 바꾸면 전부 unhealthy 다.
  3. 메모리 75% 오토스케일 정책 삭제 — 부하를 한 번 받은 태스크는 힙을 다 커밋한 채 메모리 90% 에 머물러(누수 아님)
     CPU 가 한가해도 태스크를 늘렸다. 태스크를 늘리면 RDS 커넥션만 늘어 오히려 느려진다(2차: 4대에서 RDS CPU 96%).
  4. X-Ray API 규칙 초당 1건 + 5% — DAU 2만이면 초당 10건 + 10% 로 월 ~$135(코드 커밋 5e501ec 와 같은 값).
  5. (선택) --max-inflight N: 태스크당 동시 API 요청 상한(APP_HTTP_MAX_INFLIGHT). 넘치면 1초 뒤 503 + Retry-After.
RDS 는 바꾸지 않는다 — t4g.medium 도 2 vCPU 라 CPU 병목에 효과가 없었다(2차 D1·D2)."""
import argparse, time, boto3

R = 'ap-northeast-2'
CLUSTER, SERVICE, TG_NAME = 'ruleup-prod-cluster', 'ruleup-prod-api', 'ruleup-prod-tg'
ALB_SG, ECS_SG = 'sg-03f6935fe4c111e77', 'sg-0ce610d9387070705'
MGMT = 8081

ap = argparse.ArgumentParser()
ap.add_argument('--max-inflight', type=int, default=None)
args = ap.parse_args()

ecs = boto3.client('ecs', region_name=R); elb = boto3.client('elbv2', region_name=R)
ec2 = boto3.client('ec2', region_name=R); aas = boto3.client('application-autoscaling', region_name=R)
xray = boto3.client('xray', region_name=R)
tg = elb.describe_target_groups(Names=[TG_NAME])['TargetGroups'][0]
TG = tg['TargetGroupArn']


def wait_healthy(label):
    for _ in range(40):
        states = [t['TargetHealth']['State'] for t in elb.describe_target_health(TargetGroupArn=TG)['TargetHealthDescriptions']]
        if states and all(s == 'healthy' for s in states):
            print(f'  {label}: healthy {len(states)}대'); return
        time.sleep(15)
    raise SystemExit(f'중단 — {label} 뒤 대상이 healthy 가 아니다: {states}. 헬스체크를 traffic-port·/actuator/health 로 되돌리고 확인할 것.')


# 1) ALB → ECS 8081
try:
    ec2.authorize_security_group_ingress(GroupId=ECS_SG, IpPermissions=[{'IpProtocol': 'tcp', 'FromPort': MGMT, 'ToPort': MGMT,
        'UserIdGroupPairs': [{'GroupId': ALB_SG, 'Description': 'ALB health check (management port)'}]}])
    print('SG: ALB → ECS 8081 허용')
except ec2.exceptions.ClientError as e:
    if 'Duplicate' not in str(e): raise
    print('SG: 이미 허용됨')

# 2) 옛·새 태스크 모두 응답하는 경로로
if str(tg.get('HealthCheckPort')) != str(MGMT):
    elb.modify_target_group(TargetGroupArn=TG, HealthCheckPort='traffic-port', HealthCheckPath='/health/public')
    print('헬스체크: traffic-port /health/public (전환용)'); time.sleep(40); wait_healthy('전환 경로')

# 3) 태스크 정의: 1 vCPU · 관리 포트 · (선택) 동시 상한
svc = ecs.describe_services(cluster=CLUSTER, services=[SERVICE])['services'][0]
td = ecs.describe_task_definition(taskDefinition=svc['taskDefinition'])['taskDefinition']
c = next(x for x in td['containerDefinitions'] if x['name'] == 'api')
env = {e['name']: e['value'] for e in c.get('environment', [])}
want = {'MANAGEMENT_SERVER_PORT': str(MGMT)}
if args.max_inflight: want['APP_HTTP_MAX_INFLIGHT'] = str(args.max_inflight)
ports = c.get('portMappings', [])
if td['cpu'] != '1024' or any(env.get(k) != v for k, v in want.items()) or not any(p['containerPort'] == MGMT for p in ports):
    env.update(want); c['environment'] = [{'name': k, 'value': v} for k, v in sorted(env.items())]
    if not any(p['containerPort'] == MGMT for p in ports): ports.append({'containerPort': MGMT, 'hostPort': MGMT, 'protocol': 'tcp'})
    c['portMappings'] = ports
    if c.get('cpu'): c['cpu'] = 1024
    keep = ['family', 'taskRoleArn', 'executionRoleArn', 'networkMode', 'containerDefinitions', 'volumes', 'placementConstraints',
            'requiresCompatibilities', 'runtimePlatform', 'ephemeralStorage', 'pidMode', 'ipcMode', 'proxyConfiguration']
    kw = {k: td[k] for k in keep if td.get(k) not in (None, [])}
    new = ecs.register_task_definition(cpu='1024', memory=td['memory'], **kw)['taskDefinition']['taskDefinitionArn']
    ecs.update_service(cluster=CLUSTER, service=SERVICE, taskDefinition=new)
    print(f'ECS: {new.split("/")[-1]} 배포 — 1 vCPU, 관리 포트 {MGMT}' + (f', 동시 상한 {args.max_inflight}' if args.max_inflight else ''))
    ecs.get_waiter('services_stable').wait(cluster=CLUSTER, services=[SERVICE], WaiterConfig={'Delay': 15, 'MaxAttempts': 60})
    wait_healthy('새 태스크(전환 경로)')
else:
    print('ECS: 이미 반영됨')

# 4) 헬스체크를 관리 포트로
if str(tg.get('HealthCheckPort')) != str(MGMT):
    elb.modify_target_group(TargetGroupArn=TG, HealthCheckPort=str(MGMT), HealthCheckPath='/actuator/health')
    print(f'헬스체크: {MGMT} /actuator/health'); time.sleep(40); wait_healthy('관리 포트')

# 5) 메모리 오토스케일 정책 삭제(CPU 60% 는 유지)
for p in aas.describe_scaling_policies(ServiceNamespace='ecs', ResourceId=f'service/{CLUSTER}/{SERVICE}')['ScalingPolicies']:
    if p['TargetTrackingScalingPolicyConfiguration']['PredefinedMetricSpecification']['PredefinedMetricType'] == 'ECSServiceAverageMemoryUtilization':
        aas.delete_scaling_policy(PolicyName=p['PolicyName'], ServiceNamespace='ecs', ResourceId=p['ResourceId'],
                                  ScalableDimension=p['ScalableDimension'])
        print(f'오토스케일: {p["PolicyName"]} 삭제')

# 6) X-Ray API 규칙
rule = next((r['SamplingRule'] for r in xray.get_sampling_rules()['SamplingRuleRecords'] if r['SamplingRule']['RuleName'] == 'ruleup-api'), None)
if rule and (rule['FixedRate'] != 0.05 or rule['ReservoirSize'] != 1):
    xray.update_sampling_rule(SamplingRuleUpdate={'RuleName': 'ruleup-api', 'FixedRate': 0.05, 'ReservoirSize': 1})
    print('X-Ray: ruleup-api 초당 1건 + 5%')
print('완료 — 이제 main 머지(CD 가 새 이미지로 같은 태스크 정의를 다시 띄운다)')
