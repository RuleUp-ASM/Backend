"""신규 경보만 만든다(기존 경보는 건드리지 않음). put_metric_alarm 은 같은 이름이면 갱신이라 재실행 안전."""
import boto3
R='ap-northeast-2'; A='961178969292'
cw=boto3.client('cloudwatch',region_name=R)
t=lambda n:f'arn:aws:sns:{R}:{A}:{n}'

def alarm(name, desc, actions, metric=None, stat='Sum', period=300, evals=1, dta=1, threshold=1,
          op='GreaterThanOrEqualToThreshold', metrics=None):
    kw=dict(AlarmName=name, AlarmDescription=desc, ActionsEnabled=True, AlarmActions=actions, OKActions=actions,
            EvaluationPeriods=evals, DatapointsToAlarm=dta, Threshold=threshold, ComparisonOperator=op,
            TreatMissingData='notBreaching')
    if metrics: kw['Metrics']=metrics
    else:
        ns,mn,dims=metric
        kw.update(Namespace=ns, MetricName=mn, Dimensions=[{'Name':k,'Value':v} for k,v in dims.items()],
                  Statistic=stat, Period=period)
    cw.put_metric_alarm(**kw); print('ok', name)

for env in ['prod','stg']:
    ops=[t(f'ruleup-{env}-alerts')]
    ns=f'RuleUp/App/{env}'
    p=f'ruleup-{env}'
    # --- SQS 적체·DLQ
    for q in ['moderation','notifications']:
        alarm(f'{p}-P1-sqs-{q}-backlog-age', f'{p}-{q} 큐에서 가장 오래된 메시지가 15분 넘게 처리되지 않음(10분 지속). 소비자(ECS) 정지·오류 확인.',
              ops, ('AWS/SQS','ApproximateAgeOfOldestMessage',{'QueueName':f'{p}-{q}'}), stat='Maximum', evals=2, dta=2, threshold=900,
              op='GreaterThanThreshold')
    if env=='stg':
        alarm('ruleup-stg-moderation-dlq-not-empty','검열 DLQ에 재시도 끝에 실패한 메시지가 있음.', ops,
              ('AWS/SQS','ApproximateNumberOfMessagesVisible',{'QueueName':'ruleup-stg-moderation-dlq'}), stat='Maximum', threshold=1)
    # --- 자동 인증·배치 (앱 Micrometer 지표)
    alarm(f'{p}-P1-verification-sync-failed', '인증 데이터 접수(sync)가 5분간 5건 이상 5xx로 실패. 로그에서 requestId로 추적.',
          ops, (ns,'verification.sync.failed.count',{}), threshold=5)
    if env=='prod':
        alarm(f'{p}-P0-verification-sync-failed', '인증 데이터 접수(sync) 광범위 장애 — 5분간 30건 이상 5xx.',
              ops+[t('ruleup-prod-p0-alerts')], (ns,'verification.sync.failed.count',{}), threshold=30)
    alarm(f'{p}-P1-verification-finalize-overdue', '확정 시각이 1시간 넘게 지난 미확정 판정이 15분째 남아 있음 — 확정 배치 정지·반복 실패 의심.',
          ops, (ns,'verification.finalize.overdue.value',{}), stat='Maximum', evals=3, dta=3, threshold=0, op='GreaterThanThreshold')
    alarm(f'{p}-P1-verification-finalize-failed', '판정 확정이 건별로 실패해 격리됨(0건이어야 함).',
          ops, (ns,'verification.finalize.failed.count',{}))
    alarm(f'{p}-P1-verification-materialize-failed', '무신호 귀속일 채우기 실패 — 해당 멤버·날짜 판정 행이 열리지 않음.',
          ops, (ns,'verification.materialize.failed.count',{}))
    alarm(f'{p}-P1-verification-finalize-late', '확정 배치가 03:30 reconciliation 이후까지 밀림.',
          ops, (ns,'verification.finalize.late.count',{}))
    # --- Outbox
    alarm(f'{p}-P1-outbox-pending-age', 'Outbox 에서 15분 넘게 못 나간 작업이 10분째 있음.',
          ops, (ns,'outbox.pending.oldest_age_seconds.value',{}), stat='Maximum', evals=2, dta=2, threshold=900, op='GreaterThanThreshold')
    alarm(f'{p}-P1-outbox-dead-lettered', 'Outbox 작업이 재시도 끝에 포기됨(포기 건수 증가).', ops,
          metrics=[{'Id':'m','MetricStat':{'Metric':{'Namespace':ns,'MetricName':'outbox.dead_lettered.count.value'},'Period':300,'Stat':'Maximum'},'ReturnData':False},
                   {'Id':'inc','Expression':'DIFF(m)','Label':'포기 건수 증가','ReturnData':True}],
          threshold=0, op='GreaterThanThreshold')
    # --- LLM
    alarm(f'{p}-P1-llm-failed', 'LLM 호출이 Gemini·Nova 모두 실패 — 15분간 3건 이상. llm_fail 로그로 원인 확인.',
          ops, (ns,'llm.call.failed.count',{}), period=900, threshold=3)
    alarm(f'{p}-P1-llm-latency', 'LLM 호출 평균 소요 20초 초과가 15분 지속.',
          ops, (ns,'llm.call.avg',{}), stat='Average', evals=3, dta=3, threshold=20000, op='GreaterThanThreshold')
