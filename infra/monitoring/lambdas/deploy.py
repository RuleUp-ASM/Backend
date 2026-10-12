"""감시 Lambda 배포 — 재실행 안전. IAM 관리자 자격으로 로컬에서 돌린다(경보·대시보드 스크립트와 같은 관례).

    python3 infra/monitoring/lambdas/deploy.py stg            # 전부
    python3 infra/monitoring/lambdas/deploy.py prod --only lambdas

하는 일(노션 「알람 카탈로그」 §0 실행 위치)
  iam        함수별 최소 권한 역할 3개 + Scheduler 역할. PutMetricData 는 자기 네임스페이스만.
  lambdas    slo-calculator·resource-monitor(버지니아), edge-probe(오리건·버지니아). 예약 동시성 1·비동기 재시도 1·
             이벤트 최대 120초 — 늦은 재시도가 최신 하트비트를 만들지 않게. 로그 14일.
  schedules  EventBridge Scheduler 1분, flexible window OFF, 예정 시각을 입력으로 넘긴다(멱등), 실패는 DLQ.
  taskrole   공개 API 태스크 역할에 RuleUp/SLI·Ops·Traffic·Security·DB·Observe PutMetricData 를 더한다.
  route53    NATIVE-01 헬스체크(HTTPS_STR_MATCH /health/public, 30초·3회, SNI) — Name 태그로 찾고 없으면 만든다.
  anomaly    detectors_enabled 일 때만 FLOW·HTTP·CACHE 이상 탐지 모델.
"""
import argparse
import io
import json
import os
import time
import uuid
import zipfile

import boto3

HERE = os.path.dirname(os.path.abspath(__file__))
SOURCES = ['cwio.py', 'slo_core.py', 'slo_calculator.py', 'detectors.py', 'resource_core.py', 'resource_monitor.py', 'edge_probe.py']
TASK_NAMESPACES = ['RuleUp/SLI', 'RuleUp/Ops', 'RuleUp/Traffic', 'RuleUp/Security', 'RuleUp/DB', 'RuleUp/Observe']


def load(env):
    with open(os.path.join(HERE, '..', 'env', f'{env}.json'), encoding='utf-8') as f:
        return json.load(f)


def functions(reg):
    env, mon = reg['env'], reg['monitor_region']
    out = [dict(name=f'ruleup-{env}-slo-calculator', handler='slo_calculator.handler', region=mon, role='slo', timeout=55, memory=512),
           dict(name=f'ruleup-{env}-resource-monitor', handler='resource_monitor.handler', region=mon, role='resource', timeout=55, memory=256)]
    for origin, region in reg['probe_origins'].items():
        out.append(dict(name=f'ruleup-{env}-edge-probe', handler='edge_probe.handler', region=region, role='edge', timeout=20,
                        memory=128, extra_env={'PROBE_ORIGIN': origin}))
    return out


# ---------------- IAM ----------------

def put_metrics(namespaces):
    return {'Effect': 'Allow', 'Action': 'cloudwatch:PutMetricData', 'Resource': '*',
            'Condition': {'StringEquals': {'cloudwatch:namespace': namespaces}}}


def iam(reg):
    print('== iam')
    env, acct = reg['env'], reg['account_id']
    client = boto3.client('iam')
    trust = {'Version': '2012-10-17', 'Statement': [{'Effect': 'Allow', 'Principal': {'Service': 'lambda.amazonaws.com'},
                                                     'Action': 'sts:AssumeRole'}]}
    logs = {'Effect': 'Allow', 'Action': ['logs:CreateLogStream', 'logs:PutLogEvents'],
            'Resource': f'arn:aws:logs:*:{acct}:log-group:/aws/lambda/ruleup-{env}-*:*'}
    read = {'Effect': 'Allow', 'Action': 'cloudwatch:GetMetricData', 'Resource': '*'}
    policies = {
        'slo': [logs, read, put_metrics(['RuleUp/SLO', 'RuleUp/Observe', 'RuleUp/Detectors'])],
        'resource': [logs, read, put_metrics(['RuleUp/Ops', 'RuleUp/DB', 'RuleUp/Backup', 'RuleUp/Observe']),
                     {'Effect': 'Allow', 'Resource': '*', 'Action': [
                         'ecs:DescribeServices', 'ecs:ListTasks', 'ecs:DescribeTasks',
                         'elasticloadbalancing:DescribeTargetGroups', 'elasticloadbalancing:DescribeTargetHealth',
                         'application-autoscaling:DescribeScalableTargets', 'application-autoscaling:DescribeScalingActivities',
                         'rds:DescribeDBInstances', 'rds:DescribeDBSnapshots', 'rds:DescribeEvents']}],
        'edge': [logs, put_metrics(['RuleUp/Edge', 'RuleUp/Observe'])],
    }
    for role, statements in policies.items():
        name = f'ruleup-{env}-mon-{role}'
        ensure_role(client, name, trust, f'RuleUp {env} monitoring Lambda ({role}) - infra/monitoring/lambdas')
        client.put_role_policy(RoleName=name, PolicyName='monitor', PolicyDocument=json.dumps(
            {'Version': '2012-10-17', 'Statement': statements}))
        print('  role', name)
    sched = f'ruleup-{env}-mon-scheduler'
    ensure_role(client, sched, {'Version': '2012-10-17', 'Statement': [{
        'Effect': 'Allow', 'Principal': {'Service': 'scheduler.amazonaws.com'}, 'Action': 'sts:AssumeRole',
        'Condition': {'StringEquals': {'aws:SourceAccount': acct}}}]}, f'RuleUp {env} monitoring schedules')
    client.put_role_policy(RoleName=sched, PolicyName='invoke', PolicyDocument=json.dumps({'Version': '2012-10-17', 'Statement': [
        {'Effect': 'Allow', 'Action': 'lambda:InvokeFunction', 'Resource': f'arn:aws:lambda:*:{acct}:function:ruleup-{env}-*'},
        {'Effect': 'Allow', 'Action': 'sqs:SendMessage', 'Resource': f'arn:aws:sqs:*:{acct}:ruleup-{env}-monitor-dlq'}]}))
    print('  role', sched)
    time.sleep(10)   # 새 역할이 Lambda 에 보이기까지


def ensure_role(client, name, trust, description):
    try:
        client.get_role(RoleName=name)
        client.update_assume_role_policy(RoleName=name, PolicyDocument=json.dumps(trust))
    except client.exceptions.NoSuchEntityException:
        client.create_role(RoleName=name, AssumeRolePolicyDocument=json.dumps(trust), Description=description)


# ---------------- Lambda ----------------

def package(reg):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, 'w', zipfile.ZIP_DEFLATED) as z:
        for src in SOURCES:
            z.write(os.path.join(HERE, src), src)
        z.writestr('registry.json', json.dumps(reg, ensure_ascii=False))
    return buf.getvalue()


def lambdas(reg):
    print('== lambdas')
    code = package(reg)
    acct = reg['account_id']
    for f in functions(reg):
        lam = boto3.client('lambda', region_name=f['region'])
        logs = boto3.client('logs', region_name=f['region'])
        group = f"/aws/lambda/{f['name']}"
        try:
            logs.create_log_group(logGroupName=group)
        except logs.exceptions.ResourceAlreadyExistsException:
            pass
        logs.put_retention_policy(logGroupName=group, retentionInDays=14)
        cfg = dict(FunctionName=f['name'], Runtime='python3.12', Handler=f['handler'], Timeout=f['timeout'], MemorySize=f['memory'],
                   Role=f"arn:aws:iam::{acct}:role/ruleup-{reg['env']}-mon-{f['role']}",
                   Environment={'Variables': {'RULEUP_ENV': reg['env'], **f.get('extra_env', {})}})
        try:
            lam.get_function(FunctionName=f['name'])
            lam.update_function_code(FunctionName=f['name'], ZipFile=code)
            lam.get_waiter('function_updated_v2').wait(FunctionName=f['name'])
            lam.update_function_configuration(**cfg)
        except lam.exceptions.ResourceNotFoundException:
            lam.create_function(**cfg, Code={'ZipFile': code}, Architectures=['arm64'], Publish=False)
        lam.get_waiter('function_updated_v2').wait(FunctionName=f['name'])
        try:   # 겹친 실행이 같은 분을 두 번 내지 않게. 계정 동시성 한도가 작으면 거절된다 — 그때는 알리고 진행한다.
            lam.put_function_concurrency(FunctionName=f['name'], ReservedConcurrentExecutions=1)
        except lam.exceptions.InvalidParameterValueException as e:
            print('  !! 예약 동시성 1 을 걸지 못했다:', e)
        lam.put_function_event_invoke_config(FunctionName=f['name'], MaximumRetryAttempts=1, MaximumEventAgeInSeconds=120)
        print('  function', f['region'], f['name'])


def schedules(reg):
    print('== schedules')
    env, acct = reg['env'], reg['account_id']
    role = f'arn:aws:iam::{acct}:role/ruleup-{env}-mon-scheduler'
    for f in functions(reg):
        sqs = boto3.client('sqs', region_name=f['region'])
        dlq_url = sqs.create_queue(QueueName=f'ruleup-{env}-monitor-dlq', Attributes={'MessageRetentionPeriod': '1209600'})['QueueUrl']
        dlq_arn = sqs.get_queue_attributes(QueueUrl=dlq_url, AttributeNames=['QueueArn'])['Attributes']['QueueArn']
        sch = boto3.client('scheduler', region_name=f['region'])
        kw = dict(Name=f['name'], ScheduleExpression='rate(1 minute)', FlexibleTimeWindow={'Mode': 'OFF'}, State='ENABLED',
                  Description='RuleUp monitoring - infra/monitoring/lambdas',
                  Target={'Arn': f"arn:aws:lambda:{f['region']}:{acct}:function:{f['name']}", 'RoleArn': role,
                          'Input': json.dumps({'time': '<aws.scheduler.scheduled-time>'}),
                          'RetryPolicy': {'MaximumEventAgeInSeconds': 120, 'MaximumRetryAttempts': 1},
                          'DeadLetterConfig': {'Arn': dlq_arn}})
        try:
            sch.get_schedule(Name=f['name'])
            sch.update_schedule(**kw)
        except sch.exceptions.ResourceNotFoundException:
            sch.create_schedule(**kw)
        print('  schedule', f['region'], f['name'])


# ---------------- 공개 API 태스크 역할 ----------------

def taskrole(reg):
    print('== taskrole')
    ecs = boto3.client('ecs', region_name=reg['api_region'])
    td = ecs.describe_services(cluster=reg['cluster'], services=[reg['service']])['services'][0]['taskDefinition']
    role_arn = ecs.describe_task_definition(taskDefinition=td)['taskDefinition'].get('taskRoleArn')
    if not role_arn:
        print('  !! 태스크 역할이 없다 — 앱 지표를 보낼 수 없다')
        return
    name = role_arn.split('/')[-1]
    boto3.client('iam').put_role_policy(RoleName=name, PolicyName='ruleup-ops-metrics', PolicyDocument=json.dumps(
        {'Version': '2012-10-17', 'Statement': [put_metrics(TASK_NAMESPACES)]}))
    print('  policy ruleup-ops-metrics ->', name)


# ---------------- Route 53 (NATIVE-01) ----------------

def route53(reg):
    print('== route53')
    r53 = boto3.client('route53')
    tag = f"ruleup-{reg['env']}-public"
    hc_id = find_health_check(r53, tag)
    if hc_id is None:
        hc_id = r53.create_health_check(CallerReference=str(uuid.uuid4()), HealthCheckConfig={
            'Type': 'HTTPS_STR_MATCH', 'FullyQualifiedDomainName': reg['public_domain'], 'Port': 443,
            'ResourcePath': reg['public_health_path'], 'SearchString': reg['public_health_token'],
            'RequestInterval': 30, 'FailureThreshold': 3, 'EnableSNI': True, 'MeasureLatency': False,
            'Regions': ['us-east-1', 'us-west-2', 'ap-northeast-1', 'ap-southeast-1']})['HealthCheck']['Id']
        r53.change_tags_for_resource(ResourceType='healthcheck', ResourceId=hc_id, AddTags=[{'Key': 'Name', 'Value': tag}])
    print('  health check', tag, hc_id, '— env/<env>.json route53_health_check_id 에 적어 둔다')
    return hc_id


def find_health_check(r53, tag):
    for page in r53.get_paginator('list_health_checks').paginate():
        ids = [h['Id'] for h in page['HealthChecks']]
        for i in range(0, len(ids), 10):
            for t in r53.list_tags_for_resources(ResourceType='healthcheck', ResourceIds=ids[i:i + 10])['ResourceTagSets']:
                if any(x['Key'] == 'Name' and x['Value'] == tag for x in t['Tags']):
                    return t['ResourceId']
    return None


# ---------------- 이상 탐지 모델 ----------------

def anomaly(reg):
    if not reg['rules'].get('detectors_enabled'):
        print('== anomaly (건너뜀: detectors_enabled=false)')
        return
    print('== anomaly')
    import detectors
    cw = boto3.client('cloudwatch', region_name=reg['api_region'])
    base = [{'Name': 'Env', 'Value': reg['env']}, {'Name': 'Service', 'Value': reg['metric_service']}]
    for detector, name, extra, _, _ in detectors.DETECTORS:
        dims = base + [{'Name': k, 'Value': v} for k, v in extra.items()]
        cw.put_anomaly_detector(SingleMetricAnomalyDetector={'Namespace': detectors.TRAFFIC, 'MetricName': name,
                                                             'Dimensions': dims, 'Stat': 'Sum'})
        print('  model', detector)
    dims = base + [{'Name': 'CacheKind', 'Value': 'explore'}]
    cw.put_anomaly_detector(MetricMathAnomalyDetector={'MetricDataQueries': [
        {'Id': 'hit', 'ReturnData': False, 'MetricStat': {'Metric': {'Namespace': 'RuleUp/Ops', 'MetricName': 'CacheHitCount', 'Dimensions': dims}, 'Period': 300, 'Stat': 'Sum'}},
        {'Id': 'look', 'ReturnData': False, 'MetricStat': {'Metric': {'Namespace': 'RuleUp/Ops', 'MetricName': 'CacheLookupCount', 'Dimensions': dims}, 'Period': 300, 'Stat': 'Sum'}},
        {'Id': 'm', 'Expression': 'IF(look > 0, 100 * FILL(hit, 0) / look)', 'ReturnData': True}]})
    print('  model CACHE-01')


STEPS = {'iam': iam, 'lambdas': lambdas, 'schedules': schedules, 'taskrole': taskrole, 'route53': route53, 'anomaly': anomaly}

if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('env', choices=['stg', 'prod'])
    ap.add_argument('--only', choices=list(STEPS), action='append')
    args = ap.parse_args()
    registry = load(args.env)
    for step in args.only or list(STEPS):
        STEPS[step](registry)
