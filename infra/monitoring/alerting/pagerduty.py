"""P0 온콜 — PagerDuty 에스컬레이션과 CloudWatch 연동. 재실행 안전. prod 만(stg 의 P0 는 시험 채널에만 간다).

호출 순서(2026-10-15 결정): 1차 담당(이성은)에게 바로 → 10분 안에 ACK 가 없으면 팀원 → 그래도 없으면 처음부터 다시(repeat_loops).
노션 카탈로그 「페이징 도구 결정」의 5분 지원자 통보·10분 재호출을 이 순서로 바꿨다.

    export PAGERDUTY_API_TOKEN=...            # PagerDuty REST API 키(User 또는 General Access, 쓰기). 저장소·원장에 넣지 않는다
    python3 infra/monitoring/alerting/pagerduty.py setup --primary <1차 PD 계정 이메일> --secondary <팀원 PD 계정 이메일>
    python3 infra/monitoring/alerting/p0_p1.py prod     # 원장 sns.pagerduty 가 채워졌으니 P0 경보에 토픽이 붙는다
    python3 infra/monitoring/alerting/pagerduty.py test-trigger    # 시험 경보 ALARM — Slack 두 채널 + PagerDuty. ACK 하지 말고 10분 뒤 팀원 폰을 본다
    python3 infra/monitoring/alerting/pagerduty.py test-resolve    # 시험 경보 OK — Slack 복구 알림 + PagerDuty 사건 종료

시험은 실제 P0 와 같은 길을 탄다: 서울에 시험 경보 ruleup-prod-P0-ONCALL-TEST 를 실제 P0 와 같은 AlarmActions 로 두고
set-alarm-state 로 상태만 바꾼다 → ruleup-prod-alerts(#ruleup-alert)·EventBridge 멘션(긴급 채널)·ruleup-prod-pagerduty 가 함께 받는다.
시험 경보는 아무도 내지 않는 지표(RuleUp/Observe OnCallTest)를 missing=ignore 로 보므로 다음 평가에서 상태가 되돌아가지 않는다
(다른 경보에 set-alarm-state 를 쓰면 1분 안에 OK 로 돌아가 사건이 닫혀 10분 에스컬레이션을 볼 수 없다).

setup 이 하는 일
  1. 이메일로 PagerDuty 사용자를 찾는다(두 사람 모두 PagerDuty 계정·앱 설치·전화 알림 규칙이 먼저 있어야 한다).
  2. 에스컬레이션 정책 「RuleUp prod P0」 — 규칙 1: 1차 담당, 10분 / 규칙 2: 팀원, 10분 / repeat_loops 회 반복.
  3. 서비스 「RuleUp prod P0」 — 긴급도 항상 high, ACK 후 15분 안에 해결이 없으면 다시 울림, 시간 기반 자동 종료 끔(노션 §2).
  4. 서비스에 공식 Amazon CloudWatch 연동을 붙이고, 서울·버지니아에 SNS 토픽 ruleup-prod-pagerduty 를 만들어 HTTPS 로 구독시킨다
     (CloudWatch → 같은 리전 SNS → PagerDuty 직결 — 자체 Lambda·릴레이를 거치지 않는다). 구독 확인은 PagerDuty 가 자동으로 한다.
  5. 원장 env/prod.json 에 사용자·정책·서비스 ID 와 sns.pagerduty 를 적는다(이메일·연동 키는 적지 않는다) → 커밋한다.
경보가 OK 로 돌아오면 같은 토픽(OKActions)으로 PagerDuty 사건이 닫힌다.
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
LEDGER = os.path.join(HERE, '..', 'env', 'prod.json')
API = 'https://api.pagerduty.com'
NAME = 'RuleUp prod P0'
TOPIC = 'ruleup-prod-pagerduty'
TEST_ALARM = 'ruleup-prod-P0-ONCALL-TEST'


def escalation_policy(primary_id, secondary_id, delay_minutes, loops):
    """1차 담당 → delay 분 미ACK → 팀원 → delay 분 미ACK → 처음부터(loops 회)."""
    rule = lambda uid: {'escalation_delay_in_minutes': delay_minutes, 'targets': [{'id': uid, 'type': 'user_reference'}]}
    return {'type': 'escalation_policy', 'name': NAME, 'num_loops': loops,
            'description': f'RuleUp prod P0: primary first, teammate after {delay_minutes} min without ACK',
            'escalation_rules': [rule(primary_id), rule(secondary_id)]}


def service(policy_id, ack_timeout_minutes):
    return {'type': 'service', 'name': NAME, 'description': 'CloudWatch P0 alarms (ruleup-prod-P0-*) via SNS',
            'escalation_policy': {'id': policy_id, 'type': 'escalation_policy_reference'},
            'incident_urgency_rule': {'type': 'constant', 'urgency': 'high'},
            'acknowledgement_timeout': ack_timeout_minutes * 60,
            'auto_resolve_timeout': None,
            'alert_creation': 'create_alerts_and_incidents'}


def test_alarm(reg, actions):
    """실제 P0 와 같은 전달 경로를 쓰는 시험 경보. 지표는 아무도 내지 않고 missing=ignore — set-alarm-state 로만 상태가 바뀐다."""
    return {'AlarmName': TEST_ALARM, 'ActionsEnabled': True, 'AlarmActions': actions, 'OKActions': actions,
            'AlarmDescription': '[ONCALL-TEST] 온콜 시험 경보 — pagerduty.py test-trigger/test-resolve 로만 바뀐다. 실제 장애 아님',
            'Namespace': 'RuleUp/Observe', 'MetricName': 'OnCallTest', 'Dimensions': [{'Name': 'Env', 'Value': reg['env']}],
            'Statistic': 'Maximum', 'Period': 60, 'EvaluationPeriods': 1, 'DatapointsToAlarm': 1, 'Threshold': 1,
            'ComparisonOperator': 'GreaterThanOrEqualToThreshold', 'TreatMissingData': 'ignore'}


class PagerDuty:
    def __init__(self, token):
        self.token = token

    def call(self, method, path, body=None, query=None):
        url = API + path + ('?' + urllib.parse.urlencode(query, doseq=True) if query else '')
        req = urllib.request.Request(url, method=method, data=None if body is None else json.dumps(body).encode(), headers={
            'Authorization': f'Token token={self.token}', 'Accept': 'application/vnd.pagerduty+json;version=2',
            'Content-Type': 'application/json'})
        try:
            with urllib.request.urlopen(req, timeout=20) as r:
                return json.loads(r.read() or b'{}')
        except urllib.error.HTTPError as e:
            sys.exit(f'PagerDuty {method} {path} → {e.code}: {e.read().decode(errors="replace")[:300]}')

    def user_id(self, email):
        users = [u for u in self.call('GET', '/users', query={'query': email})['users'] if u['email'].lower() == email.lower()]
        if not users:
            sys.exit(f'PagerDuty 사용자가 없다 — 먼저 초대·가입시킨다: {email}')
        return users[0]['id']

    def find(self, kind, name):
        key = kind.rstrip('s') if kind != 'escalation_policies' else 'escalation_policy'
        hits = [x for x in self.call('GET', f'/{kind}', query={'query': name})[kind] if x['name'] == name]
        return hits[0]['id'] if hits else None, key

    def upsert(self, kind, body):
        found, key = self.find(kind, body['name'])
        if found:
            return self.call('PUT', f'/{kind}/{found}', {key: body})[key]['id']
        return self.call('POST', f'/{kind}', {key: body})[key]['id']

    def cloudwatch_integration_key(self, service_id):
        svc = self.call('GET', f'/services/{service_id}', query={'include[]': 'integrations'})['service']
        for i in svc.get('integrations', []):
            if i.get('type') == 'aws_cloudwatch_inbound_integration':
                return self.call('GET', f'/services/{service_id}/integrations/{i["id"]}')['integration']['integration_key']
        vendors = [v for v in self.call('GET', '/vendors', query={'query': 'Amazon CloudWatch'})['vendors'] if v['name'] == 'Amazon CloudWatch']
        if not vendors:
            sys.exit('PagerDuty 에서 Amazon CloudWatch 연동(vendor)을 찾지 못했다')
        body = {'type': 'aws_cloudwatch_inbound_integration', 'name': 'CloudWatch via SNS',
                'vendor': {'id': vendors[0]['id'], 'type': 'vendor_reference'}}
        return self.call('POST', f'/services/{service_id}/integrations', {'integration': body})['integration']['integration_key']


def load():
    with open(LEDGER, encoding='utf-8') as f:
        return json.load(f)


def save(reg):
    with open(LEDGER, 'w', encoding='utf-8') as f:
        json.dump(reg, f, ensure_ascii=False, indent=2)
        f.write('\n')


def regions(reg):
    return [reg['api_region'], reg['monitor_region']]


def subscribe(reg, endpoint):
    import boto3
    sys.path.insert(0, HERE)
    import routing  # 토픽 정책(계정 소유자 + CloudWatch 발행)을 같은 모양으로
    for region in regions(reg):
        sns = boto3.client('sns', region_name=region)
        arn = routing.topic(sns, TOPIC, reg['account_id'], ['cloudwatch.amazonaws.com'])
        subs = [s for p in sns.get_paginator('list_subscriptions_by_topic').paginate(TopicArn=arn) for s in p['Subscriptions']]
        for s in subs:   # 연동을 다시 만들어 키가 바뀌었으면 옛 구독을 지운다
            if s['Protocol'] == 'https' and s['Endpoint'] != endpoint and s['SubscriptionArn'].startswith('arn:'):
                sns.unsubscribe(SubscriptionArn=s['SubscriptionArn'])
                print('  unsubscribed old endpoint', region)
        if not any(s['Endpoint'] == endpoint for s in subs):
            sns.subscribe(TopicArn=arn, Protocol='https', Endpoint=endpoint)
        print('  subscribed', region, TOPIC)


def setup(args):
    token = os.environ.get('PAGERDUTY_API_TOKEN') or sys.exit('PAGERDUTY_API_TOKEN 이 없다')
    reg = load()
    pd_cfg = reg['pagerduty']
    pd = PagerDuty(token)
    primary, secondary = pd.user_id(args.primary), pd.user_id(args.secondary)
    policy_id = pd.upsert('escalation_policies', escalation_policy(primary, secondary, pd_cfg['escalation_delay_minutes'], pd_cfg['repeat_loops']))
    print('escalation policy', policy_id, f"(1차 → {pd_cfg['escalation_delay_minutes']}분 → 팀원, {pd_cfg['repeat_loops']}회 반복)")
    service_id = pd.upsert('services', service(policy_id, pd_cfg['ack_timeout_minutes']))
    print('service', service_id)
    key = pd.cloudwatch_integration_key(service_id)
    subscribe(reg, f'https://events.pagerduty.com/integration/{key}/enqueue')
    pd_cfg.update(primary_user_id=primary, secondary_user_id=secondary, escalation_policy_id=policy_id, service_id=service_id)
    reg['sns']['pagerduty'] = TOPIC
    save(reg)
    print('원장 env/prod.json 갱신 — 커밋하고 p0_p1.py prod 를 다시 돌려 P0 경보에 토픽을 붙인다')


def set_test_state(state):
    import boto3
    sys.path.insert(0, HERE)
    import p0_p1  # 실제 P0 와 같은 AlarmActions(운영 Slack·긴급 P0 토픽·PagerDuty)
    reg = load()
    if not reg['sns'].get('pagerduty'):
        sys.exit('원장 sns.pagerduty 가 비어 있다 — setup 먼저')
    region = reg['api_region']
    cw = boto3.client('cloudwatch', region_name=region)
    cw.put_metric_alarm(**test_alarm(reg, p0_p1.actions(reg, region, 'P0')))
    if state == 'ALARM':   # 이미 ALARM 이면 상태 변경이 없어 아무 데도 안 간다 — OK 를 거쳐 다시 울린다
        current = cw.describe_alarms(AlarmNames=[TEST_ALARM])['MetricAlarms'][0]['StateValue']
        if current == 'ALARM':
            sys.exit('시험 경보가 이미 ALARM 이다 — test-resolve 로 닫고 1분 뒤 다시')
    cw.set_alarm_state(AlarmName=TEST_ALARM, StateValue=state, StateReason=f'pagerduty.py on-call test ({state})')
    print(state, TEST_ALARM, region, '→', ', '.join(a.split(':')[-1] for a in p0_p1.actions(reg, region, 'P0')))
    if state == 'ALARM':
        print('Slack #ruleup-alert·긴급 채널 멘션·PagerDuty 가 함께 와야 한다. ACK 하지 말 것 — 10분 뒤 팀원, 확인 후 test-resolve',
              time.strftime('%H:%M:%S'))


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest='cmd', required=True)
    s = sub.add_parser('setup')
    s.add_argument('--primary', required=True, help='1차 담당 PagerDuty 계정 이메일')
    s.add_argument('--secondary', required=True, help='10분 미응답 시 받을 팀원 PagerDuty 계정 이메일')
    sub.add_parser('test-trigger')
    sub.add_parser('test-resolve')
    a = ap.parse_args()
    if a.cmd == 'setup':
        setup(a)
    else:
        set_test_state('ALARM' if a.cmd == 'test-trigger' else 'OK')
