"""계정 보안 이벤트 → 운영 Slack(P1) — 노션 「알람 카탈로그」 §6.1 SEC-02·03·04. 재실행 안전.

    python3 infra/monitoring/alerting/security_events.py

  SEC-02 GuardDuty Finding severity ≥4 (각 사용 리전) — GuardDuty 가 켜진 리전에서만 이벤트가 온다
  SEC-03 IAM 키·정책·역할 신뢰 변경(us-east-1 — IAM 은 글로벌 이벤트) · 보안그룹 규칙 변경(서울)
  SEC-04 root 콘솔 로그인 성공·실패(us-east-1)
SEC-03/04 는 CloudTrail 관리 이벤트가 EventBridge 로 와야 한다(트레일 필요). 탐지 자체는 침해 확정이 아니다(RB-05).
승인된 CI 변경도 그대로 알린다 — 배포 역할 전체를 무시하지 않는다.
"""
import json
import os

import boto3

HERE = os.path.dirname(os.path.abspath(__file__))
IAM_EVENTS = ['CreateAccessKey', 'UpdateAssumeRolePolicy', 'AttachRolePolicy', 'PutRolePolicy', 'CreatePolicyVersion', 'SetDefaultPolicyVersion']
SG_EVENTS = ['AuthorizeSecurityGroupIngress', 'AuthorizeSecurityGroupEgress', 'RevokeSecurityGroupIngress',
             'RevokeSecurityGroupEgress', 'ModifySecurityGroupRules']


def load(env):
    with open(os.path.join(HERE, '..', 'env', f'{env}.json'), encoding='utf-8') as f:
        return json.load(f)


def rule(region, name, pattern, topic, title, fields):
    ev = boto3.client('events', region_name=region)
    ev.put_rule(Name=name, EventPattern=json.dumps(pattern), State='ENABLED',
                Description='RuleUp security P1 - infra/monitoring/alerting/security_events.py')
    paths = {k: v for k, v in fields.items()}
    body = '\\n'.join(f'{k}=<{k}>' for k in fields) + '\\n런북 RB-05 — 탐지는 침해 확정이 아니다'
    template = '{"version":"1.0","source":"custom","content":{"textType":"client-markdown","title":"' + title + '","description":"' + body + '"}}'
    ev.put_targets(Rule=name, Targets=[{'Id': 'slack', 'Arn': topic, 'InputTransformer': {'InputPathsMap': paths, 'InputTemplate': template}}])
    print('rule', region, name)


def allow_events(region, topic):
    sns = boto3.client('sns', region_name=region)
    policy = json.loads(sns.get_topic_attributes(TopicArn=topic)['Attributes']['Policy'])
    if not any(s.get('Principal', {}).get('Service') == 'events.amazonaws.com' for s in policy['Statement']):
        policy['Statement'].append({'Sid': 'EventBridge', 'Effect': 'Allow', 'Principal': {'Service': 'events.amazonaws.com'},
                                    'Action': 'SNS:Publish', 'Resource': topic})
        sns.set_topic_attributes(TopicArn=topic, AttributeName='Policy', AttributeValue=json.dumps(policy))


if __name__ == '__main__':
    reg = load('prod')
    acct, api, mon = reg['account_id'], reg['api_region'], reg['monitor_region']
    topic = lambda region: f"arn:aws:sns:{region}:{acct}:{reg['sns']['alerts']}"
    for region in (api, mon):
        allow_events(region, topic(region))
        rule(region, 'ruleup-SEC-02-guardduty', {'source': ['aws.guardduty'], 'detail-type': ['GuardDuty Finding'],
                                                 'detail': {'severity': [{'numeric': ['>=', 4]}]}},
             topic(region), ':shield: [SEC-02] GuardDuty finding',
             {'type': '$.detail.type', 'severity': '$.detail.severity', 'id': '$.detail.id', 'updatedAt': '$.detail.updatedAt'})
    rule(mon, 'ruleup-SEC-03-iam-change', {'source': ['aws.iam'], 'detail-type': ['AWS API Call via CloudTrail'],
                                           'detail': {'eventSource': ['iam.amazonaws.com'], 'eventName': IAM_EVENTS}},
         topic(mon), ':shield: [SEC-03] IAM 변경', {'event': '$.detail.eventName', 'actor': '$.detail.userIdentity.arn', 'time': '$.detail.eventTime'})
    rule(api, 'ruleup-SEC-03-sg-change', {'source': ['aws.ec2'], 'detail-type': ['AWS API Call via CloudTrail'],
                                          'detail': {'eventSource': ['ec2.amazonaws.com'], 'eventName': SG_EVENTS}},
         topic(api), ':shield: [SEC-03] 보안그룹 변경', {'event': '$.detail.eventName', 'actor': '$.detail.userIdentity.arn', 'time': '$.detail.eventTime'})
    rule(mon, 'ruleup-SEC-04-root-login', {'detail-type': ['AWS Console Sign In via CloudTrail'],
                                           'detail': {'userIdentity': {'type': ['Root']}}},
         topic(mon), ':shield: [SEC-04] root 콘솔 로그인', {'result': '$.detail.responseElements.ConsoleLogin', 'ip': '$.detail.sourceIPAddress', 'time': '$.detail.eventTime'})
