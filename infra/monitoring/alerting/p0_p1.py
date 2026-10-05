"""노션 「RuleUp 모니터링」 3절(알림 기준) 의 인프라 경보 — P0/P1 임계값의 원본. 재실행 안전.
앱 지표 경보(sync·확정·Outbox·LLM·SQS 적체)는 alarms.py 가 만든다. 이 파일은 그 밖의 서버·인프라 경보와
옛 이름 경보의 P0/P1 정리를 맡는다. 임계값을 바꿀 때는 노션과 이 파일을 같이 고친다.

P0 → prod 는 ruleup-prod-alerts + ruleup-prod-p0-alerts(EventBridge 가 긴급 채널 멘션으로 바꾼다), stg 는 ruleup-stg-alerts 만.
P1 → ruleup-<env>-alerts.

CloudWatch 한계로 노션과 다른 점
- 「정상 서버 0대 30초」: ALB HealthyHostCount 는 1분 해상도라 30초 경보를 걸 수 없다 → 1분 1회로 둔다(가장 짧은 값).
- 「Redis 캐시 히트율 급락」: 고정 임계값이 없어 이상 탐지 밴드(평소 범위 아래로 15분)로 잡는다. 학습에 약 2주 걸린다.
"""
import boto3
R='ap-northeast-2'; A='961178969292'
cw=boto3.client('cloudwatch',region_name=R)
t=lambda n:f'arn:aws:sns:{R}:{A}:{n}'
GB=1024**3

ENV={
    'prod':dict(lb='app/ruleup-prod-alb/30526d51ec29a005', tg='targetgroup/ruleup-prod-tg/d3db371c903b8892',
                cluster='ruleup-prod-cluster', service='ruleup-prod-api', rds='ruleup-prod-mysql',
                redis=['ruleup-prod-redis-001','ruleup-prod-redis-002'], fcm_ns='RuleUp/Prod/Notification', min_tasks=2),
    'stg':dict(lb='app/ruleup-stg-alb/1a2d3584a7aaa2c1', tg='targetgroup/ruleup-stg-tg/864594f20fdc7ddd',
               cluster='ruleup-stg-cluster', service='ruleup-stg-api', rds='ruleup-stg-mysql-v2',
               redis=['ruleup-stg-redis'], fcm_ns='RuleUp/Notification', min_tasks=1),
}
# 이름을 P0/P1 규칙으로 바꾸거나 등급을 옮긴 경보 — 새 이름을 만든 뒤 지운다.
RETIRED={
    'prod':['ruleup-prod-P0-api-5xx-target','ruleup-prod-moderation-dlq-not-empty','ruleup-prod-notifications-dlq-not-empty',
            'ruleup-prod-notification-fcm-success-rate','ruleup-prod-rds-connections'],
    'stg':['ruleup-stg-P0-api-5xx-target','ruleup-stg-moderation-dlq-not-empty','ruleup-stg-notification-dlq',
           'ruleup-stg-notification-fcm-success-rate'],
}

def put(name, desc, actions, *, ns=None, metric=None, dims=None, stat='Average', period=60, evals=1, dta=None,
        threshold=None, op='GreaterThanThreshold', missing='notBreaching', metrics=None, band_id=None):
    kw=dict(AlarmName=name, AlarmDescription=desc, ActionsEnabled=True, AlarmActions=actions, OKActions=actions,
            EvaluationPeriods=evals, DatapointsToAlarm=dta or evals, ComparisonOperator=op, TreatMissingData=missing)
    if band_id: kw.update(Metrics=metrics, ThresholdMetricId=band_id)
    elif metrics: kw.update(Metrics=metrics, Threshold=threshold)
    else:
        kw.update(Namespace=ns, MetricName=metric, Dimensions=[{'Name':k,'Value':v} for k,v in dims.items()],
                  Period=period, Threshold=threshold)
        kw['ExtendedStatistic' if stat.startswith('p') else 'Statistic']=stat
    cw.put_metric_alarm(**kw); print('ok', name)

for env,c in ENV.items():
    p=f'ruleup-{env}'
    p1=[t(f'{p}-alerts')]
    p0=p1+([t('ruleup-prod-p0-alerts')] if env=='prod' else [])
    alb={'LoadBalancer':c['lb'],'TargetGroup':c['tg']}
    ecs={'ClusterName':c['cluster'],'ServiceName':c['service']}
    rds={'DBInstanceIdentifier':c['rds']}

    # ---------- P0: 즉시 확인 ----------
    put(f'{p}-P0-api-no-healthy-target','정상 서버 0대 — 요청을 받을 태스크가 없음. ECS 서비스 이벤트(배포·Spot 회수·크래시)부터 확인.',
        p0, ns='AWS/ApplicationELB', metric='HealthyHostCount', dims=alb, stat='Minimum', period=60, evals=1,
        threshold=1, op='LessThanThreshold', missing='breaching')
    put(f'{p}-P0-ecs-cpu-critical','ECS CPU 95% 이상 5분 — 응답 지연·헬스체크 실패 직전. 태스크 수·트래픽 급증·배치 폭주 확인.',
        p0, ns='AWS/ECS', metric='CPUUtilization', dims=ecs, period=60, evals=5, threshold=95, op='GreaterThanOrEqualToThreshold')
    put(f'{p}-P0-ecs-memory-critical','ECS 메모리 97% 이상 5분 — OOM 으로 태스크가 죽기 직전.',
        p0, ns='AWS/ECS', metric='MemoryUtilization', dims=ecs, period=60, evals=5, threshold=97, op='GreaterThanOrEqualToThreshold')
    put(f'{p}-P0-api-latency-p95-critical','API p95 3초 초과 5분 — 사용자 체감 장애. 트레이스(X-Ray)에서 느린 구간 확인.',
        p0, ns='AWS/ApplicationELB', metric='TargetResponseTime', dims=alb, stat='p95', period=60, evals=5, threshold=3)
    put(f'{p}-P0-rds-{c["rds"]}-storage-low','RDS 남은 저장 공간 5GB 미만 — 다 차면 쓰기가 전부 멈춘다.',
        p0, ns='AWS/RDS', metric='FreeStorageSpace', dims=rds, stat='Minimum', period=300, threshold=5*GB, op='LessThanThreshold')
    # 「서비스 전체 장애」— 요청 절반 이상이 5xx(요청 20건 미만 구간은 판단하지 않는다).
    put(f'{p}-P0-api-5xx-ratio','5분간 요청의 50% 이상이 5xx — 서비스 전체 장애.', p0, threshold=50, op='GreaterThanOrEqualToThreshold',
        metrics=[{'Id':'req','MetricStat':{'Metric':{'Namespace':'AWS/ApplicationELB','MetricName':'RequestCount','Dimensions':[{'Name':'LoadBalancer','Value':c['lb']}]},'Period':300,'Stat':'Sum'},'ReturnData':False},
                 {'Id':'e5','MetricStat':{'Metric':{'Namespace':'AWS/ApplicationELB','MetricName':'HTTPCode_ELB_5XX_Count','Dimensions':[{'Name':'LoadBalancer','Value':c['lb']}]},'Period':300,'Stat':'Sum'},'ReturnData':False},
                 {'Id':'t5','MetricStat':{'Metric':{'Namespace':'AWS/ApplicationELB','MetricName':'HTTPCode_Target_5XX_Count','Dimensions':[{'Name':'LoadBalancer','Value':c['lb']},{'Name':'TargetGroup','Value':c['tg']}]},'Period':300,'Stat':'Sum'},'ReturnData':False},
                 {'Id':'ratio','Expression':'IF(FILL(req,0) >= 20, 100*(FILL(e5,0)+FILL(t5,0))/req, 0)','Label':'5xx 비율(%)','ReturnData':True}])

    # ---------- P1: 빠른 확인 ----------
    if c['min_tasks']>1:
        put(f'{p}-P1-api-hosts-reduced',f'정상 서버가 {c["min_tasks"]}대 아래로 줄어 2분 지속 — 한 대만 남아 여유가 없음.',
            p1, ns='AWS/ApplicationELB', metric='HealthyHostCount', dims=alb, stat='Minimum', period=60, evals=2,
            threshold=c['min_tasks'], op='LessThanThreshold')
    put(f'{p}-P1-ecs-cpu-high','ECS CPU 85% 이상 10분.', p1, ns='AWS/ECS', metric='CPUUtilization', dims=ecs, period=60, evals=10,
        threshold=85, op='GreaterThanOrEqualToThreshold')
    put(f'{p}-P1-ecs-memory-high','ECS 메모리 90% 이상 10분.', p1, ns='AWS/ECS', metric='MemoryUtilization', dims=ecs, period=60, evals=10,
        threshold=90, op='GreaterThanOrEqualToThreshold')
    put(f'{p}-P1-api-latency-p95','API p95 1.5초 초과 10분.', p1, ns='AWS/ApplicationELB', metric='TargetResponseTime', dims=alb,
        stat='p95', period=60, evals=10, threshold=1.5)
    put(f'{p}-P1-api-5xx-target','앱이 낸 5xx 가 5분간 10건 이상 — 코드 오류. 로그에서 requestId 로 추적.',
        p1, ns='AWS/ApplicationELB', metric='HTTPCode_Target_5XX_Count', dims=alb, stat='Sum', period=300, threshold=10,
        op='GreaterThanOrEqualToThreshold')
    put(f'{p}-P1-rds-connections','RDS 연결 수 100 초과 10분 — 커넥션 누수·풀 설정 확인.', p1, ns='AWS/RDS', metric='DatabaseConnections',
        dims=rds, stat='Maximum', period=300, evals=2, threshold=100)
    for q in ['moderation','notifications']:
        put(f'{p}-P1-sqs-{q}-dlq',f'{p}-{q} DLQ 에 재시도 끝에 실패한 메시지가 있음.', p1, ns='AWS/SQS',
            metric='ApproximateNumberOfMessagesVisible', dims={'QueueName':f'{p}-{q}-dlq'}, stat='Maximum', period=300,
            threshold=1, op='GreaterThanOrEqualToThreshold')
    # FCM: 15분 동안 5건 이상 보냈는데 성공률 90% 미만. 처음엔 타이트하게 두고 오탐을 보며 낮춘다.
    put(f'{p}-P1-fcm-success-rate','FCM 성공률 90% 미만(15분, 5건 이상 발송 시). 토큰 위생과 UNAVAILABLE·SENDER_ID_MISMATCH 비율 확인.',
        p1, threshold=90, op='LessThanThreshold',
        metrics=[{'Id':'ok','MetricStat':{'Metric':{'Namespace':c['fcm_ns'],'MetricName':'PushSuccess'},'Period':900,'Stat':'Sum'},'ReturnData':False},
                 {'Id':'ng','MetricStat':{'Metric':{'Namespace':c['fcm_ns'],'MetricName':'PushFailed'},'Period':900,'Stat':'Sum'},'ReturnData':False},
                 {'Id':'rate','Expression':'IF(FILL(ok,0)+FILL(ng,0) >= 5, 100*FILL(ok,0)/(FILL(ok,0)+FILL(ng,0)), 100)','Label':'FCM 성공률(%)','ReturnData':True}])
    for node in c['redis']:
        put(f'{p}-P1-redis-{node}-hit-rate-drop',f'Redis({node}) 캐시 히트율이 평소 범위 아래로 15분 — 키 만료·eviction·워밍업 실패 확인.',
            p1, op='LessThanLowerThreshold', evals=3, band_id='band',
            metrics=[{'Id':'m','MetricStat':{'Metric':{'Namespace':'AWS/ElastiCache','MetricName':'CacheHitRate','Dimensions':[{'Name':'CacheClusterId','Value':node}]},'Period':300,'Stat':'Average'},'ReturnData':True},
                     {'Id':'band','Expression':'ANOMALY_DETECTION_BAND(m, 2)','Label':'평소 범위','ReturnData':True}])

    cw.delete_alarms(AlarmNames=RETIRED[env]); print('retired', RETIRED[env])
