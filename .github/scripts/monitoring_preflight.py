"""배포 사전 점검 — 노션 「알람 카탈로그」 §9.1·G04 「deployment_preflight」. 배포 워크플로가 ECS 갱신 직전에 부른다.

    python3 .github/scripts/monitoring_preflight.py <stg|prod> [--mode warn|enforce]

막는 것(enforce 에서 exit 1)
  1. ALARM 상태인 연결 경보 — ECS 서비스 deployment alarms + 이 환경의 P0 경보(서울·중앙) + 관측 하트비트(OBS-01)
  2. CUJ Operation 원천 RuleUp/SLI EligibleCount(Env,Service,Operation) — 수집 지연 120초를 뺀 최근 완료 3구간 중 실제 데이터포인트 2개 미만.
     0 은 「실제로 보낸 0」이면 인정하고, 빈 Timestamps 는 실패다(FILL 하지 않는다)
  3. 원천이 전부 0 인데 ALB 에 실요청이 있다 — 라우트 계측 누락
  4. BudgetFreeze=1 인 Operation — 완전한 30일·≥1,000건·coverage ≥99% 에서 버짓 소진(저표본은 0 이라 막지 않는다)
경고만(통과): 저표본 P0 Flag 의 INSUFFICIENT_DATA — 위 원천·하트비트·백스톱이 정상일 때만.
예외: INCIDENT_ROLLBACK_ID 가 있으면(승인된 사고 중 B 복구) 1·4 를 경고로 낮춘다. 2·3 은 그대로 본다.

기본 모드는 warn 이다 — 감시 Lambda·앱 계측이 처음 올라가는 동안 배포를 막지 않으려고. 저장소 변수 MONITORING_PREFLIGHT=enforce 로 켠다.
"""
import argparse
import json
import os
import sys
from datetime import datetime, timedelta, timezone

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
sys.path.insert(0, os.path.join(ROOT, 'infra', 'monitoring', 'lambdas'))
import slo_core  # noqa: E402


def decide(alarms, raw_counts, alb_requests, budget_freeze, incident=None):
    """(차단 사유 목록, 경고 목록). 순수 함수 — 시험한다.

    alarms        [(이름, 상태)] — 연결 경보·P0·하트비트
    raw_counts    {operation: [최근 완료 3구간 값, 없으면 None]}
    alb_requests  같은 3구간 ALB 요청 합(None 이면 모름)
    budget_freeze {operation: 0|1|None}
    """
    block, warn = [], []
    soft = (lambda m: warn.append(f'[사고 {incident} 예외] {m}')) if incident else block.append
    for name, state in alarms:
        if state == 'ALARM':
            soft(f'ALARM: {name}')
        elif state == 'INSUFFICIENT_DATA':
            warn.append(f'INSUFFICIENT_DATA(저표본 가능): {name}')
    for op in slo_core.OPERATIONS:
        values = raw_counts.get(op, [])
        present = [v for v in values if v is not None]
        if len(present) < 2:
            block.append(f'원천 누락: RuleUp/SLI EligibleCount Operation={op} — 최근 완료 3구간 중 데이터 {len(present)}개')
    total = sum(v for vals in raw_counts.values() for v in vals if v is not None)
    if alb_requests and alb_requests >= 20 and total == 0:
        block.append(f'계측 불일치: ALB 요청 {int(alb_requests)}건인데 CUJ EligibleCount 합이 0 — 라우트 계측 누락 의심')
    for op, freeze in budget_freeze.items():
        if freeze == 1:
            soft(f'버짓 소진으로 기능 배포 중단: {op} (재개는 §8 승인 경로)')
    return block, warn


def collect(reg, env):
    import boto3
    api, mon = reg['api_region'], reg['monitor_region']
    svc, prefix = reg['metric_service'], f'ruleup-{env}-'
    names = []
    try:
        s = boto3.client('ecs', region_name=api).describe_services(cluster=reg['cluster'], services=[reg['service']])['services'][0]
        names += ((s.get('deploymentConfiguration') or {}).get('alarms') or {}).get('alarmNames', [])
    except Exception as e:  # noqa: BLE001
        print(f'::warning::ECS 배포 경보 목록을 못 읽었다: {e}')
    alarms = []
    for region in (api, mon):
        cw = boto3.client('cloudwatch', region_name=region)
        for p in (f'{prefix}P0-', f'{prefix}P1-OBS-01-'):
            for page in cw.get_paginator('describe_alarms').paginate(AlarmNamePrefix=p, AlarmTypes=['MetricAlarm', 'CompositeAlarm']):
                alarms += [(a['AlarmName'], a['StateValue']) for a in page['MetricAlarms'] + page['CompositeAlarms']]
        if names:
            for page in cw.get_paginator('describe_alarms').paginate(AlarmNames=names[:100]):
                alarms += [(a['AlarmName'], a['StateValue']) for a in page['MetricAlarms']]
    now = datetime.now(timezone.utc).replace(second=0, microsecond=0) - timedelta(seconds=120)
    end = now.replace(second=0)
    start = end - timedelta(minutes=3)
    cw = boto3.client('cloudwatch', region_name=api)
    ops = list(slo_core.OPERATIONS)
    q = [{'Id': f'e{i}', 'MetricStat': {'Metric': {'Namespace': 'RuleUp/SLI', 'MetricName': 'EligibleCount', 'Dimensions': [
        {'Name': 'Env', 'Value': env}, {'Name': 'Service', 'Value': svc}, {'Name': 'Operation', 'Value': op}]}, 'Period': 60, 'Stat': 'Sum'}}
         for i, op in enumerate(ops)]
    q.append({'Id': 'alb', 'MetricStat': {'Metric': {'Namespace': 'AWS/ApplicationELB', 'MetricName': 'RequestCount', 'Dimensions': [
        {'Name': 'LoadBalancer', 'Value': reg['load_balancer']}, {'Name': 'TargetGroup', 'Value': reg['target_group']}]}, 'Period': 180, 'Stat': 'Sum'}})
    res = {r['Id']: r for r in cw.get_metric_data(MetricDataQueries=q, StartTime=start, EndTime=end)['MetricDataResults']}
    buckets = [start + timedelta(minutes=k) for k in range(3)]
    raw = {}
    for i, op in enumerate(ops):
        got = {t.replace(second=0, microsecond=0): v for t, v in zip(res[f'e{i}']['Timestamps'], res[f'e{i}']['Values'])}
        raw[op] = [got.get(b) for b in buckets]
    alb = sum(res['alb']['Values']) if res['alb']['Values'] else None
    freeze = {}
    mcw = boto3.client('cloudwatch', region_name=mon)
    fq = [{'Id': f'f{i}', 'MetricStat': {'Metric': {'Namespace': 'RuleUp/SLO', 'MetricName': 'BudgetFreeze', 'Dimensions': [
        {'Name': 'Env', 'Value': env}, {'Name': 'Service', 'Value': svc}, {'Name': 'Operation', 'Value': op}]}, 'Period': 900, 'Stat': 'Maximum'}}
          for i, op in enumerate(ops)]
    fres = mcw.get_metric_data(MetricDataQueries=fq, StartTime=end - timedelta(minutes=30), EndTime=end)['MetricDataResults']
    for r in fres:
        freeze[ops[int(r['Id'][1:])]] = r['Values'][0] if r['Values'] else None
    return alarms, raw, alb, freeze


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('env', choices=['stg', 'prod'])
    ap.add_argument('--mode', choices=['warn', 'enforce'], default='warn')
    a = ap.parse_args()
    with open(os.path.join(ROOT, 'infra', 'monitoring', 'env', f'{a.env}.json'), encoding='utf-8') as f:
        registry = json.load(f)
    try:
        block, warn = decide(*collect(registry, a.env), incident=os.environ.get('INCIDENT_ROLLBACK_ID') or None)
    except Exception as e:  # noqa: BLE001 — 조회 오류·권한 오류도 막는 사유다(노션 §9.1)
        block, warn = [f'사전 점검 조회 실패: {e}'], []
    for w in warn:
        print(f'::warning::{w}')
    for b in block:
        print(('::error::' if a.mode == 'enforce' else '::warning::[enforce 시 차단] ') + b)
    print(f'monitoring preflight mode={a.mode} block={len(block)} warn={len(warn)}')
    sys.exit(1 if block and a.mode == 'enforce' else 0)
