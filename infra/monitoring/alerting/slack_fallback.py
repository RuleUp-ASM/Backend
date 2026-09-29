"""Slack 이 주 경로, 이메일은 Slack 전달이 실패했을 때만. 재실행 안전. p0_mention.py 다음에 실행한다.
실패 감지 3가지 → 대체 토픽 ruleup-alert-fallback-email(이메일)로 알린다:
  1) SNS → Amazon Q 전달 실패       AWS/SNS NumberOfNotificationsFailed (Slack 토픽별)
  2) Amazon Q → Slack 게시 실패      /aws/chatbot/<구성> 오류 로그 (us-east-1, 구성 로그 레벨 ERROR)
  3) EventBridge → SNS 전달 실패     AWS/Events FailedInvocations (커스텀 알림 규칙별)
대체 토픽 구독은 확인 메일의 링크를 눌러야 활성화된다(서울·us-east-1 두 통).
기존 Slack 토픽의 이메일 구독 해지는 이 스크립트가 하지 않는다 — 확인 링크를 누른 뒤 README 의 명령으로 해지한다."""
import boto3, json
R='ap-northeast-2'; US='us-east-1'; A='961178969292'; MAIL='se021012@naver.com'
FB='ruleup-alert-fallback-email'
SLACK_TOPICS=['ruleup-prod-p0-slack','ruleup-prod-alerts','ruleup-stg-alerts']
RULES=['ruleup-prod-p0-alarm','ruleup-prod-p0-recovered','ruleup-prod-ecs-task-crashed','ruleup-stg-ecs-task-crashed']
CHATBOT_CONFIGS=['ruleup-p0-urgent','ruleup-ops']

def fallback_topic(region):
    sns=boto3.client('sns',region_name=region)
    t=sns.create_topic(Name=FB)['TopicArn']
    sns.set_topic_attributes(TopicArn=t,AttributeName='Policy',AttributeValue=json.dumps({"Version":"2012-10-17","Statement":[
        {"Sid":"Owner","Effect":"Allow","Principal":{"AWS":"*"},"Action":["SNS:Publish","SNS:Subscribe","SNS:GetTopicAttributes","SNS:SetTopicAttributes","SNS:ListSubscriptionsByTopic"],"Resource":t,"Condition":{"StringEquals":{"AWS:SourceOwner":A}}},
        {"Sid":"CloudWatchAlarms","Effect":"Allow","Principal":{"Service":"cloudwatch.amazonaws.com"},"Action":"SNS:Publish","Resource":t}]}))
    subs=sns.list_subscriptions_by_topic(TopicArn=t)['Subscriptions']
    if not any(s['Protocol']=='email' and s['Endpoint']==MAIL for s in subs):
        sns.subscribe(TopicArn=t,Protocol='email',Endpoint=MAIL); print('confirm mail sent',region)
    return t

def alarm(cw,name,desc,ns,metric,dims,topic):
    cw.put_metric_alarm(AlarmName=name,AlarmDescription=desc,Namespace=ns,MetricName=metric,Dimensions=dims,
        Statistic='Sum',Period=300,EvaluationPeriods=1,DatapointsToAlarm=1,Threshold=1,
        ComparisonOperator='GreaterThanOrEqualToThreshold',TreatMissingData='notBreaching',AlarmActions=[topic])
    print('alarm',name)

fb=fallback_topic(R); fb_us=fallback_topic(US)
cw=boto3.client('cloudwatch',region_name=R); cw_us=boto3.client('cloudwatch',region_name=US)
HINT='이 사이 Slack 에 못 간 경보가 있다. CloudWatch 콘솔(ap-northeast-2) 경보 목록에서 현재 상태를 확인할 것.'
for t in SLACK_TOPICS:
    alarm(cw,f'ruleup-slack-fallback-sns-{t}',f'SNS 가 {t} 알림을 Amazon Q 로 전달하지 못함. '+HINT,
          'AWS/SNS','NumberOfNotificationsFailed',[{'Name':'TopicName','Value':t}],fb)
for r in RULES:
    alarm(cw,f'ruleup-slack-fallback-events-{r}',f'EventBridge 규칙 {r} 이 SNS 로 알림을 넘기지 못함. '+HINT,
          'AWS/Events','FailedInvocations',[{'Name':'RuleName','Value':r}],fb)
logs=boto3.client('logs',region_name=US)
for c in CHATBOT_CONFIGS:
    g=f'/aws/chatbot/{c}'
    try: logs.create_log_group(logGroupName=g)
    except logs.exceptions.ResourceAlreadyExistsException: pass
    logs.put_retention_policy(logGroupName=g,retentionInDays=30)
    # 로그 레벨이 ERROR 지만, Slack 에서 실행한 명령의 감사 로그("User … ran command …")는 항상 같은 그룹에 쌓인다 — 제외.
    logs.put_metric_filter(logGroupName=g,filterName='errors',filterPattern='-"ran command"',
        metricTransformations=[{'metricName':f'ChatbotErrors-{c}','metricNamespace':'RuleUp/Alerting','metricValue':'1','defaultValue':0}])
    alarm(cw_us,f'ruleup-slack-fallback-chatbot-{c}',f'Amazon Q 가 Slack({c}) 에 알림을 올리지 못함({g} 로그 확인). '+HINT,
          'RuleUp/Alerting',f'ChatbotErrors-{c}',[],fb_us)
