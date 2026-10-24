"""노션 「알람 카탈로그 · SLO 구현 계약」의 경보를 만든다 — 정의는 catalog.py, 이 파일은 적용만. 재실행 안전.

    python3 infra/monitoring/alerting/p0_p1.py stg
    python3 infra/monitoring/alerting/p0_p1.py prod --dry-run     # 만들 경보 목록만 출력

전달
  P0 → <리전> ruleup-<env>-alerts + ruleup-prod-p0-alerts(긴급 채널 멘션은 EventBridge 가 ruleup-prod-P0- 접두어로 잡는다)
       + 원장의 PagerDuty 토픽(sns.pagerduty, 개통 전에는 null 이라 건너뛴다). stg 는 P0 도 시험 채널(ruleup-stg-alerts)만.
  P1 → ruleup-<env>-alerts
리전: SLO·EDGE·TLS·NATIVE-01·RES·관측 하트비트는 중앙 감시 리전(us-east-1), 나머지는 API 리전(서울).
중앙 리전 토픽·Slack 연결은 routing.py 가 만든다(먼저 실행).
옛 이름 경보(catalog.RETIRED)는 새 경보를 만든 뒤 지운다.
"""
import argparse
import json
import os
import sys
from datetime import datetime
from zoneinfo import ZoneInfo

import boto3

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import catalog  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
KST = ZoneInfo('Asia/Seoul')


def load(env):
    with open(os.path.join(HERE, '..', 'env', f'{env}.json'), encoding='utf-8') as f:
        return json.load(f)


def health_check_id(reg):
    if reg.get('route53_health_check_id'):
        return reg['route53_health_check_id']
    r53 = boto3.client('route53')
    tag = f"ruleup-{reg['env']}-public"
    for page in r53.get_paginator('list_health_checks').paginate():
        ids = [h['Id'] for h in page['HealthChecks']]
        for i in range(0, len(ids), 10):
            for t in r53.list_tags_for_resources(ResourceType='healthcheck', ResourceIds=ids[i:i + 10])['ResourceTagSets']:
                if any(x['Key'] == 'Name' and x['Value'] == tag for x in t['Tags']):
                    return t['ResourceId']
    print('!! Route 53 헬스체크가 없다 — lambdas/deploy.py --only route53 를 먼저 돌린다. NATIVE-01 을 건너뛴다.')
    return None


def actions(reg, region, severity):
    arn = lambda name: f"arn:aws:sns:{region}:{reg['account_id']}:{name}"
    out = [arn(reg['sns']['alerts'])]
    if severity == 'P0':
        out += [arn(t) for t in (reg['sns'].get('p0'), reg['sns'].get('pagerduty')) if t]
    return out


def apply(reg, dry_run=False):
    specs = catalog.build(reg, None if dry_run else health_check_id(reg))
    # 밤에 다시 돌려도 night_hold.py 가 꺼 둔 야간 보류 경보를 켜 버리지 않는다.
    quiet = set(catalog.night_held(reg)) if catalog.night_hold_quiet(datetime.now(KST).time()) else set()
    clients = {}
    for region, spec in specs:
        if dry_run:
            print(f"{region:15} {spec['severity']:4} {spec['name']}")
            continue
        cw = clients.setdefault(region, boto3.client('cloudwatch', region_name=region))
        params = dict(spec['params'])
        if spec['severity'] == 'leaf':
            params.update(ActionsEnabled=False, AlarmActions=[], OKActions=[])
        else:
            acts = actions(reg, region, spec['severity'])
            params.update(ActionsEnabled=spec['name'] not in quiet, AlarmActions=acts, OKActions=acts)
        if spec['kind'] == 'composite':
            cw.put_composite_alarm(**params)
        else:
            cw.put_metric_alarm(**params)
        print('ok', region, spec['name'])
    print(f'{len(specs)} alarms')
    if dry_run:
        return
    retired = catalog.RETIRED[reg['env']]
    seoul = boto3.client('cloudwatch', region_name=reg['api_region'])
    for i in range(0, len(retired), 100):
        seoul.delete_alarms(AlarmNames=retired[i:i + 100])
    print('retired', len(retired))


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('env', choices=['stg', 'prod'])
    ap.add_argument('--dry-run', action='store_true')
    a = ap.parse_args()
    apply(load(a.env), a.dry_run)
