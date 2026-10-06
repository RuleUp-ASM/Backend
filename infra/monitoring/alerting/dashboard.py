"""서버(운영) 대시보드 ruleup-<env>-ops. 재실행 안전.
prod 는 이 화면 + 사용자 지표(business_dashboard.py), stg 는 이 화면만 둔다 — stg 의 가입·로그인 수는 QA 가 만든 값이라
추세로 볼 의미가 없고, stg 에서 미리 잡아야 하는 건 배포 뒤 서버·배치·외부 연동이 멀쩡한지다.
사용: python dashboard.py [prod|stg]"""
import boto3, json, sys
R='ap-northeast-2'
ENV=sys.argv[1] if len(sys.argv)>1 else 'prod'
C={
    'prod':dict(lb='app/ruleup-prod-alb/30526d51ec29a005', tg='targetgroup/ruleup-prod-tg/d3db371c903b8892',
                rds='ruleup-prod-mysql', redis='ruleup-prod-redis-001', fcm_ns='RuleUp/Prod/Notification'),
    'stg':dict(lb='app/ruleup-stg-alb/1a2d3584a7aaa2c1', tg='targetgroup/ruleup-stg-tg/864594f20fdc7ddd',
               rds='ruleup-stg-mysql-v2', redis='ruleup-stg-redis', fcm_ns='RuleUp/Notification'),
}[ENV]
cw=boto3.client('cloudwatch',region_name=R)
LB,TG=C['lb'],C['tg']; NS=f'RuleUp/App/{ENV}'; P=f'ruleup-{ENV}'; LOG=f'/ecs/{P}-api'
CONSOLE=f'https://{R}.console.aws.amazon.com/cloudwatch/home?region={R}'
widgets=[]; y=0
def text(md, h=1):
    global y; widgets.append({'type':'text','x':0,'y':y,'width':24,'height':h,'properties':{'markdown':md}}); y+=h
def row(*ws):
    global y; x=0
    for title,metrics,extra in ws:
        w=24//len(ws)
        p={'title':title,'region':R,'view':'timeSeries','stacked':False,'period':300,'metrics':metrics}; p.update(extra or {})
        widgets.append({'type':'metric','x':x,'y':y,'width':w,'height':6,'properties':p}); x+=w
    y+=6
alb=lambda m,s,**o:['AWS/ApplicationELB',m,'LoadBalancer',LB,'TargetGroup',TG,{'stat':s,**o}]
widgets.append({'type':'alarm','x':0,'y':0,'width':24,'height':3,'properties':{'title':f'경보 상태 ({ENV})','alarms':[
    a['AlarmArn'] for pg in cw.get_paginator('describe_alarms').paginate(AlarmNamePrefix=f'{P}-') for a in pg['MetricAlarms']][:100],'sortBy':'stateUpdatedTimestamp','states':['ALARM','INSUFFICIENT_DATA','OK']}}); y=3
# 지표로 이상을 봤으면 다음은 트레이스(어느 구간이 느린가)와 로그(왜 실패했나). 로그 줄의 [requestId traceId] 로 둘을 잇는다.
text(f'**로그** [{LOG}]({CONSOLE}#logsV2:log-groups/log-group/{LOG.replace("/","$252F")}) · '
     f'[Logs Insights]({CONSOLE}#logsV2:logs-insights) · [Live Tail]({CONSOLE}#logsV2:live-tail)　　'
     f'**트레이스** [X-Ray 트레이스]({CONSOLE}#xray:traces/query) · [서비스 맵]({CONSOLE}#xray:service-map/map) — '
     f'필터 `service("ruleup-api-{ENV}") AND annotation.http_route = "/api/v1/..."`(배치·봇 요청 제외), 로그의 traceId `abcd1234…` 는 X-Ray 에서 `1-abcd1234-…` 로 찾는다'
     + ('' if ENV=='stg' else f'　　**사용자 지표** 대시보드 `{P}-business`'))
text('## API')
row(('요청 수',[alb('RequestCount','Sum')],None),
    ('5xx',[alb('HTTPCode_Target_5XX_Count','Sum',label='타깃 5xx'),['AWS/ApplicationELB','HTTPCode_ELB_5XX_Count','LoadBalancer',LB,{'stat':'Sum','label':'ALB 5xx'}]],None),
    ('응답 시간(초)',[alb('TargetResponseTime','p95',label='p95'),alb('TargetResponseTime','p50',label='p50')],None),
    ('정상 타깃 수',[alb('HealthyHostCount','Minimum')],None))
text('## 서버 · DB · Redis')
ecs=lambda m:['AWS/ECS',m,'ClusterName',f'{P}-cluster','ServiceName',f'{P}-api',{'stat':'Average'}]
rds=lambda m,s='Average':['AWS/RDS',m,'DBInstanceIdentifier',C['rds'],{'stat':s}]
red=lambda m,**o:['AWS/ElastiCache',m,'CacheClusterId',C['redis'],{'stat':'Average',**o}]
row(('ECS CPU·메모리(%)',[ecs('CPUUtilization'),ecs('MemoryUtilization')],None),
    ('RDS CPU(%)·연결 수',[rds('CPUUtilization'),rds('DatabaseConnections','Maximum')],None),
    ('RDS 남은 저장공간(byte)',[rds('FreeStorageSpace','Minimum')],None),
    ('Redis CPU·메모리·캐시 히트율(%)',[red('EngineCPUUtilization'),red('DatabaseMemoryUsagePercentage'),red('CacheHitRate',label='캐시 히트율')],None))
text('## 자동 인증 · 배치 · 처리 대기 작업')
app=lambda m,s='Sum',**o:[NS,m,{'stat':s,**o}]
sqs=lambda q,m,s='Maximum':['AWS/SQS',m,'QueueName',q,{'stat':s,'label':f'{q}'}]
row(('인증 접수 실패(sync 5xx)',[app('verification.sync.failed.count')],None),
    # 확정 오류 = 건별 확정 실패 + 무신호 귀속일 채우기 실패(채우기가 실패하면 그날 판정 자체가 없어 다른 선에 안 잡힌다).
    ('확정 배치',[app('verification.finalize.overdue.value','Maximum',label='1시간 넘게 미확정'),
                 [{'expression':'FILL(ff,0)+FILL(mf,0)','label':'확정 오류','id':'err'}],
                 app('verification.finalize.failed.count',id='ff',visible=False),app('verification.materialize.failed.count',id='mf',visible=False),
                 app('verification.finalize.no_signal.count',label='신호 없이 실패',yAxis='right')],None),
    ('Outbox',[app('outbox.pending.oldest_age_seconds.value','Maximum',label='가장 오래된 대기(초)'),app('outbox.dead_lettered.count.value','Maximum',label='포기 누적',yAxis='right')],None),
    ('SQS 가장 오래된 메시지(초)·DLQ',[sqs(f'{P}-moderation','ApproximateAgeOfOldestMessage'),sqs(f'{P}-notifications','ApproximateAgeOfOldestMessage'),
                                       sqs(f'{P}-moderation-dlq','ApproximateNumberOfMessagesVisible')[:-1]+[{'stat':'Maximum','label':'moderation DLQ','yAxis':'right'}],
                                       sqs(f'{P}-notifications-dlq','ApproximateNumberOfMessagesVisible')[:-1]+[{'stat':'Maximum','label':'notifications DLQ','yAxis':'right'}]],None))
text('## 외부 연동')
row(('FCM 푸시 성공·실패·성공률',[[C['fcm_ns'],'PushSuccess',{'stat':'Sum','id':'ok'}],[C['fcm_ns'],'PushFailed',{'stat':'Sum','id':'ng'}],
                                 [{'expression':'IF(FILL(ok,0)+FILL(ng,0)>0, 100*FILL(ok,0)/(FILL(ok,0)+FILL(ng,0)))','label':'성공률(%)','id':'rate','yAxis':'right'}]],None),
    ('LLM 호출·최종 실패',[app('llm.call.count',label='호출'),app('llm.call.failed.count',label='최종 실패')],None),
    ('LLM 소요 시간(ms)',[app('llm.call.avg','Average',label='평균'),app('llm.call.max','Maximum',label='최대')],None))
text('## 최근 오류 로그')
# 대시보드를 열 때마다 Logs Insights 가 이 범위를 훑는다(스캔 GB 과금) — 그래서 ERROR 만, 50줄만.
widgets.append({'type':'log','x':0,'y':y,'width':24,'height':8,'properties':{'title':f'ERROR 로그 ({LOG})','region':R,'view':'table',
    'query':f"SOURCE '{LOG}' | fields @timestamp, @message | filter @message like / ERROR / | sort @timestamp desc | limit 50"}}); y+=8
cw.put_dashboard(DashboardName=f'{P}-ops',DashboardBody=json.dumps({'widgets':widgets},ensure_ascii=False))
print('ok', f'{P}-ops', len(widgets))
