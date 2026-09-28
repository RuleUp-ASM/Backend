import boto3, json
R='ap-northeast-2'; A='961178969292'
sns=boto3.client('sns',region_name=R); iam=boto3.client('iam'); cb=boto3.client('chatbot',region_name='us-east-2')
arn=lambda n:f'arn:aws:sns:{R}:{A}:{n}'
# 1) P0 전용 토픽
p0=sns.create_topic(Name='ruleup-prod-p0-alerts')['TopicArn']
sns.set_topic_attributes(TopicArn=p0,AttributeName='Policy',AttributeValue=json.dumps({"Version":"2012-10-17","Statement":[
 {"Sid":"Owner","Effect":"Allow","Principal":{"AWS":"*"},"Action":["SNS:Publish","SNS:Subscribe","SNS:GetTopicAttributes","SNS:SetTopicAttributes","SNS:ListSubscriptionsByTopic"],"Resource":p0,"Condition":{"StringEquals":{"AWS:SourceOwner":A}}},
 {"Sid":"CloudWatchAlarms","Effect":"Allow","Principal":{"Service":"cloudwatch.amazonaws.com"},"Action":"SNS:Publish","Resource":p0},
 {"Sid":"EventBridge","Effect":"Allow","Principal":{"Service":"events.amazonaws.com"},"Action":"SNS:Publish","Resource":p0}]}))
# 2) Amazon Q 역할(읽기 전용)
role='ruleup-chatbot-notify'
try:
  iam.create_role(RoleName=role,AssumeRolePolicyDocument=json.dumps({"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"chatbot.amazonaws.com"},"Action":"sts:AssumeRole"}]}),Description='Amazon Q Slack alert delivery (read-only)')
except iam.exceptions.EntityAlreadyExistsException: pass
iam.attach_role_policy(RoleName=role,PolicyArn='arn:aws:iam::aws:policy/CloudWatchReadOnlyAccess')
rarn=f'arn:aws:iam::{A}:role/{role}'
ro='arn:aws:iam::aws:policy/CloudWatchReadOnlyAccess'
existing={c['SlackChannelId']:c for c in cb.describe_slack_channel_configurations()['SlackChannelConfigurations']}
for ch,name,topics in [('C0C4HUAHBHV','ruleup-p0-urgent',[p0]),('C0C4Z5VJJNN','ruleup-ops',[arn('ruleup-prod-alerts'),arn('ruleup-stg-alerts')])]:
  kw=dict(SlackChannelId=ch,IamRoleArn=rarn,SnsTopicArns=topics,GuardrailPolicyArns=[ro],LoggingLevel='ERROR')
  if ch in existing: cb.update_slack_channel_configuration(ChatConfigurationArn=existing[ch]['ChatConfigurationArn'],**kw)
  else:
    import time; time.sleep(8)
    cb.create_slack_channel_configuration(SlackTeamId='T0B0RJLAWL8',ConfigurationName=name,**kw)
  print('ok',name)
