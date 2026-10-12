"""중앙 감시 리전(us-east-1)에도 경보 토픽을 두고 기존 Slack 채널에 잇는다. 재실행 안전. p0_p1.py 보다 먼저 실행한다.

    python3 infra/monitoring/alerting/routing.py

  ruleup-<env>-alerts (us-east-1)      → Amazon Q 구성 ruleup-ops(#ruleup-alert) 에 추가
  ruleup-prod-p0-alerts (us-east-1)    → 경보 AlarmActions 용(구독 없음 — 서울과 같은 구조)
  ruleup-prod-p0-slack (us-east-1)     → EventBridge(us-east-1) 가 P0 상태 변경을 멘션 알림으로 바꿔 보낸다 → ruleup-p0-urgent
서울 쪽 구성(slack_channels.py·p0_mention.py)은 그대로 두고 토픽 목록에 us-east-1 것을 더한다.
SNS 는 전달만 한다 — ACK·에스컬레이션은 PagerDuty 개통 후(원장 sns.pagerduty)에 생긴다.
"""
import json
import os

import boto3

HERE = os.path.dirname(os.path.abspath(__file__))
CHATBOT_REGION = 'us-east-2'


def load(env):
    with open(os.path.join(HERE, '..', 'env', f'{env}.json'), encoding='utf-8') as f:
        return json.load(f)


def topic(sns, name, account, publishers):
    arn = sns.create_topic(Name=name)['TopicArn']
    statements = [{'Sid': 'Owner', 'Effect': 'Allow', 'Principal': {'AWS': '*'},
                   'Action': ['SNS:Publish', 'SNS:Subscribe', 'SNS:GetTopicAttributes', 'SNS:SetTopicAttributes', 'SNS:ListSubscriptionsByTopic'],
                   'Resource': arn, 'Condition': {'StringEquals': {'AWS:SourceOwner': account}}}]
    statements += [{'Sid': svc.split('.')[0], 'Effect': 'Allow', 'Principal': {'Service': svc}, 'Action': 'SNS:Publish', 'Resource': arn}
                   for svc in publishers]
    sns.set_topic_attributes(TopicArn=arn, AttributeName='Policy', AttributeValue=json.dumps({'Version': '2012-10-17', 'Statement': statements}))
    print('topic', arn)
    return arn


def mention_rules(reg, slack_topic):
    """p0_mention.py 와 같은 규칙을 중앙 리전에도 — 경보 상태 변경 이벤트는 리전 안에서만 나온다."""
    region, member = reg['monitor_region'], reg['slack_p0_mention_member']
    ev = boto3.client('events', region_name=region)
    console = f'https://{region}.console.aws.amazon.com/cloudwatch/home?region={region}#alarmsV2:alarm/'
    for name, state, prev, title, text in (
            ('ruleup-prod-p0-alarm', 'ALARM', None, ':rotating_light: [P0] <name>', f'<@{member}> 즉시 확인 필요\n<reason>\n{console}<name>'),
            ('ruleup-prod-p0-recovered', 'OK', 'ALARM', ':white_check_mark: [P0 복구] <name>', f'<reason>\n{console}<name>')):
        pattern = {'source': ['aws.cloudwatch'], 'detail-type': ['CloudWatch Alarm State Change'],
                   'detail': {'alarmName': [{'prefix': 'ruleup-prod-P0-'}], 'state': {'value': [state]}}}
        if prev:
            pattern['detail']['previousState'] = {'value': [prev]}
        ev.put_rule(Name=name, EventPattern=json.dumps(pattern), State='ENABLED')
        template = json.dumps({'version': '1.0', 'source': 'custom', 'content': {'textType': 'client-markdown', 'title': title, 'description': text},
                               'metadata': {'threadId': '<name>'}}, ensure_ascii=False)
        ev.put_targets(Rule=name, Targets=[{'Id': 'slack', 'Arn': slack_topic, 'InputTransformer': {
            'InputPathsMap': {'name': '$.detail.alarmName', 'reason': '$.detail.state.reason'}, 'InputTemplate': template}}])
        print('rule', region, name)


def attach(config_name, topic_arns):
    cb = boto3.client('chatbot', region_name=CHATBOT_REGION)
    c = next(c for c in cb.describe_slack_channel_configurations()['SlackChannelConfigurations'] if c['ConfigurationName'] == config_name)
    topics = sorted(set(c['SnsTopicArns']) | set(topic_arns))
    cb.update_slack_channel_configuration(ChatConfigurationArn=c['ChatConfigurationArn'], SlackChannelId=c['SlackChannelId'],
                                          IamRoleArn=c['IamRoleArn'], GuardrailPolicyArns=c['GuardrailPolicyArns'],
                                          LoggingLevel=c['LoggingLevel'], SnsTopicArns=topics)
    print('chatbot', config_name, len(topics), 'topics')


if __name__ == '__main__':
    prod, stg = load('prod'), load('stg')
    region, account = prod['monitor_region'], prod['account_id']
    sns = boto3.client('sns', region_name=region)
    cw_publish = ['cloudwatch.amazonaws.com']
    ops_topics = [topic(sns, prod['sns']['alerts'], account, cw_publish), topic(sns, stg['sns']['alerts'], account, cw_publish)]
    topic(sns, prod['sns']['p0'], account, cw_publish + ['events.amazonaws.com'])
    slack_p0 = topic(sns, 'ruleup-prod-p0-slack', account, ['events.amazonaws.com'])
    mention_rules(prod, slack_p0)
    attach('ruleup-ops', ops_topics)
    attach('ruleup-p0-urgent', [slack_p0])
