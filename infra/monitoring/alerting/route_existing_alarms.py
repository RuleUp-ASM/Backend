"""기존 경보 재배선 — prod P0 경보를 긴급 토픽에도 보내고, 모든 경보가 복구(OK)도 알리게 한다.
ECS 크래시 이벤트는 Amazon Q 가 Slack 에 그릴 수 있는 커스텀 알림 형식으로 바꾼다. 재실행 안전."""
import boto3, json
R='ap-northeast-2'; A='961178969292'
cw=boto3.client('cloudwatch',region_name=R); ev=boto3.client('events',region_name=R)
P0=f'arn:aws:sns:{R}:{A}:ruleup-prod-p0-alerts'
KEYS=['AlarmName','AlarmDescription','ActionsEnabled','OKActions','AlarmActions','InsufficientDataActions','MetricName',
      'Namespace','Statistic','ExtendedStatistic','Dimensions','Period','Unit','EvaluationPeriods','DatapointsToAlarm',
      'Threshold','ComparisonOperator','TreatMissingData','EvaluateLowSampleCountPercentile','Metrics','ThresholdMetricId']
for page in cw.get_paginator('describe_alarms').paginate(AlarmNamePrefix='ruleup-',AlarmTypes=['MetricAlarm']):
    for a in page['MetricAlarms']:
        acts=list(dict.fromkeys(a['AlarmActions']+([P0] if a['AlarmName'].startswith('ruleup-prod-P0-') else [])))
        if acts==a['AlarmActions'] and a['OKActions']==acts: continue
        kw={k:a[k] for k in KEYS if a.get(k) not in (None,[])}
        kw['AlarmActions']=acts; kw['OKActions']=acts
        cw.put_metric_alarm(**kw); print('updated',a['AlarmName'])
for env in ['prod','stg']:
    rule=f'ruleup-{env}-ecs-task-crashed'
    t=ev.list_targets_by_rule(Rule=rule)['Targets'][0]
    t['InputTransformer']['InputTemplate']=json.dumps({"version":"1.0","source":"custom","content":{
        "textType":"client-markdown","title":f":rotating_light: [ruleup-{env}] ECS 태스크 비정상 종료",
        "description":"group=<group>\nstopCode=<code>\nreason=<reason>\ntask=<task>"}},ensure_ascii=False)
    ev.put_targets(Rule=rule,Targets=[t]); print('event target',rule)
