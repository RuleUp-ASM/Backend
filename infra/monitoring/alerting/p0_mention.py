"""긴급 채널(#ruleup-alert-urgent)의 P0 알림에 담당자 멘션을 붙인다. 재실행 안전.
경보 기본 알림은 멘션을 못 넣으므로, 경보 상태 변경 이벤트를 EventBridge 가 Amazon Q 커스텀 알림으로 바꿔
새 토픽 ruleup-prod-p0-slack 으로 보내고, 긴급 채널 구성을 그 토픽으로 옮긴다.
P0 경보의 AlarmActions(ruleup-prod-p0-alerts)는 건드리지 않는다 — 그 토픽은 구독자 없이 남는다.
사용: python p0_mention.py [Slack 멤버 ID]"""
import boto3, json, sys
R='ap-northeast-2'; A='961178969292'; MEMBER=sys.argv[1] if len(sys.argv)>1 else 'U0AVBAWH05Q'  # 이성은
sns=boto3.client('sns',region_name=R); ev=boto3.client('events',region_name=R); cb=boto3.client('chatbot',region_name='us-east-2')
t=sns.create_topic(Name='ruleup-prod-p0-slack')['TopicArn']
sns.set_topic_attributes(TopicArn=t,AttributeName='Policy',AttributeValue=json.dumps({"Version":"2012-10-17","Statement":[
    {"Sid":"Owner","Effect":"Allow","Principal":{"AWS":"*"},"Action":["SNS:Publish","SNS:Subscribe","SNS:GetTopicAttributes","SNS:SetTopicAttributes","SNS:ListSubscriptionsByTopic"],"Resource":t,"Condition":{"StringEquals":{"AWS:SourceOwner":A}}},
    {"Sid":"EventBridge","Effect":"Allow","Principal":{"Service":"events.amazonaws.com"},"Action":"SNS:Publish","Resource":t}]}))
CONSOLE=f'https://{R}.console.aws.amazon.com/cloudwatch/home?region={R}#alarmsV2:alarm/'
PATHS={'name':'$.detail.alarmName','reason':'$.detail.state.reason'}
def rule(name,state,prev,title,desc):
    pat={"source":["aws.cloudwatch"],"detail-type":["CloudWatch Alarm State Change"],
         "detail":{"alarmName":[{"prefix":"ruleup-prod-P0-"}],"state":{"value":[state]}}}
    if prev: pat['detail']['previousState']={"value":[prev]}
    ev.put_rule(Name=name,EventPattern=json.dumps(pat),State='ENABLED')
    tmpl=json.dumps({"version":"1.0","source":"custom","content":{"textType":"client-markdown","title":title,"description":desc},
                     "metadata":{"threadId":"<name>"}},ensure_ascii=False)
    ev.put_targets(Rule=name,Targets=[{'Id':'slack','Arn':t,'InputTransformer':{'InputPathsMap':PATHS,'InputTemplate':tmpl}}])
    print('rule',name)
rule('ruleup-prod-p0-alarm','ALARM',None,':rotating_light: [P0] <name>',
     f'<@{MEMBER}> 즉시 확인 필요\n<reason>\n{CONSOLE}<name>')
# 복구는 멘션 없이 같은 스레드로. INSUFFICIENT_DATA→OK(경보 생성 직후 등)는 알리지 않는다.
rule('ruleup-prod-p0-recovered','OK','ALARM',':white_check_mark: [P0 복구] <name>',f'<reason>\n{CONSOLE}<name>')
c=next(c for c in cb.describe_slack_channel_configurations()['SlackChannelConfigurations'] if c['ConfigurationName']=='ruleup-p0-urgent')
cb.update_slack_channel_configuration(ChatConfigurationArn=c['ChatConfigurationArn'],SlackChannelId=c['SlackChannelId'],
    IamRoleArn=c['IamRoleArn'],GuardrailPolicyArns=c['GuardrailPolicyArns'],LoggingLevel=c['LoggingLevel'],SnsTopicArns=[t])
print('urgent channel ->',t)
