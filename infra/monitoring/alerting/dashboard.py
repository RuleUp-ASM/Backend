import boto3, json
R='ap-northeast-2'; A='961178969292'
cw=boto3.client('cloudwatch',region_name=R)
LB='app/ruleup-prod-alb/30526d51ec29a005'; TG='targetgroup/ruleup-prod-tg/d3db371c903b8892'
NS='RuleUp/App/prod'
widgets=[]; y=0
def text(md):
    global y; widgets.append({'type':'text','x':0,'y':y,'width':24,'height':1,'properties':{'markdown':md}}); y+=1
def row(*ws):
    global y; x=0
    for title,metrics,extra in ws:
        w=24//len(ws)
        p={'title':title,'region':R,'view':'timeSeries','stacked':False,'period':300,'metrics':metrics}; p.update(extra or {})
        widgets.append({'type':'metric','x':x,'y':y,'width':w,'height':6,'properties':p}); x+=w
    y+=6
alb=lambda m,s,**o:['AWS/ApplicationELB',m,'LoadBalancer',LB,'TargetGroup',TG,{'stat':s,**o}]
widgets.append({'type':'alarm','x':0,'y':0,'width':24,'height':3,'properties':{'title':'경보 상태 (prod)','alarms':[
    a['AlarmArn'] for pg in cw.get_paginator('describe_alarms').paginate(AlarmNamePrefix='ruleup-prod-') for a in pg['MetricAlarms']][:100],'sortBy':'stateUpdatedTimestamp','states':['ALARM','INSUFFICIENT_DATA','OK']}}); y=3
text('## API')
row(('요청 수',[alb('RequestCount','Sum')],None),
    ('5xx',[alb('HTTPCode_Target_5XX_Count','Sum',label='타깃 5xx'),['AWS/ApplicationELB','HTTPCode_ELB_5XX_Count','LoadBalancer',LB,{'stat':'Sum','label':'ALB 5xx'}]],None),
    ('응답 시간(초)',[alb('TargetResponseTime','p95',label='p95'),alb('TargetResponseTime','p50',label='p50')],None),
    ('정상 타깃 수',[alb('HealthyHostCount','Minimum')],None))
text('## 서버 · DB · Redis')
ecs=lambda m:['AWS/ECS',m,'ClusterName','ruleup-prod-cluster','ServiceName','ruleup-prod-api',{'stat':'Average'}]
rds=lambda m,s='Average':['AWS/RDS',m,'DBInstanceIdentifier','ruleup-prod-mysql',{'stat':s}]
red=lambda m:['AWS/ElastiCache',m,'CacheClusterId','ruleup-prod-redis-001',{'stat':'Average'}]
row(('ECS CPU·메모리(%)',[ecs('CPUUtilization'),ecs('MemoryUtilization')],None),
    ('RDS CPU(%)·연결 수',[rds('CPUUtilization'),rds('DatabaseConnections','Maximum')+[]],None),
    ('RDS 남은 저장공간(byte)',[rds('FreeStorageSpace','Minimum')],None),
    ('Redis CPU·메모리(%)',[red('EngineCPUUtilization'),red('DatabaseMemoryUsagePercentage')],None))
text('## 자동 인증 · 배치 · 처리 대기 작업')
app=lambda m,s='Sum',**o:[NS,m,{'stat':s,**o}]
sqs=lambda q,m,s='Maximum':['AWS/SQS',m,'QueueName',q,{'stat':s,'label':f'{q}'}]
row(('인증 접수 실패(sync 5xx)',[app('verification.sync.failed.count')],None),
    ('확정 배치: 미확정·실패·지연',[app('verification.finalize.overdue.value','Maximum',label='1시간 넘게 미확정'),app('verification.finalize.failed.count',label='확정 실패'),
                                    app('verification.materialize.failed.count',label='채우기 실패'),app('verification.finalize.late.count',label='03:30 이후 확정')],None),
    ('Outbox',[app('outbox.pending.oldest_age_seconds.value','Maximum',label='가장 오래된 대기(초)'),app('outbox.dead_lettered.count.value','Maximum',label='포기 누적',yAxis='right')],None),
    ('SQS 가장 오래된 메시지(초)·DLQ',[sqs('ruleup-prod-moderation','ApproximateAgeOfOldestMessage'),sqs('ruleup-prod-notifications','ApproximateAgeOfOldestMessage'),
                                       sqs('ruleup-prod-moderation-dlq','ApproximateNumberOfMessagesVisible')[:-1]+[{'stat':'Maximum','label':'moderation DLQ','yAxis':'right'}],
                                       sqs('ruleup-prod-notifications-dlq','ApproximateNumberOfMessagesVisible')[:-1]+[{'stat':'Maximum','label':'notifications DLQ','yAxis':'right'}]],None))
text('## 외부 연동')
row(('FCM 푸시 성공·실패',[['RuleUp/Prod/Notification','PushSuccess',{'stat':'Sum'}],['RuleUp/Prod/Notification','PushFailed',{'stat':'Sum'}]],None),
    ('LLM 호출·최종 실패',[app('llm.call.count',label='호출'),app('llm.call.failed.count',label='최종 실패')],None),
    ('LLM 소요 시간(ms)',[app('llm.call.avg','Average',label='평균'),app('llm.call.max','Maximum',label='최대')],None))
cw.put_dashboard(DashboardName='ruleup-prod-ops',DashboardBody=json.dumps({'widgets':widgets},ensure_ascii=False))
print('ok', len(widgets))
