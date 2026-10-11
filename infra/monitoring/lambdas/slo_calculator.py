"""SLO 계산기 — 노션 「알람 카탈로그」 §0·§2. 버지니아 Scheduler(1분) → 이 함수.

1. 서울 RuleUp/SLI 의 Operation 별 원천 카운터 3종을 최근 72시간 1분 해상도로 읽는다(수집 지연 120초를 뺀 마지막 완료 분까지).
2. Rule 7개(SLO-01~04·LAT-01·API-01/02)를 두 창 AND·표본 게이트까지 판정해 RuleUp/SLO AlarmFlag(Env,Service,Operation,Rule) 로 낸다.
   입력 부족이면 그 Rule 의 Flag 를 내지 않는다(0 으로 메우지 않는다 — slo_core 참고).
3. refresh·sync 의 BurnRate 4창, 15분마다 30일 BudgetRemainingPct·BudgetFreeze(배포 사전 점검용).
4. 원천 조회가 전부 끝나면 RuleUp/Observe CollectorHeartbeat(Component=slo-calculator) — OBS-01·OBS-04 의 근거.
5. detectors_enabled 이면 5분마다 FLOW·HTTP·CACHE 이상 탐지 Flag(RuleUp/Detectors).

노션 설계와 다른 점: 분 버킷을 DynamoDB 에 따로 쌓지 않는다. CloudWatch 가 1분 해상도를 15일 보관하고 GetMetricData 는
지표 개수로 과금되므로(데이터포인트 수 무관) 72시간을 매분 다시 읽는 비용이 원천 30개 기준 월 약 130만 조회 단위로 노션 추정과 같다.
30일 버짓은 1시간 해상도로 15분마다 읽는다.
"""
from datetime import timedelta, timezone, datetime

import boto3

import cwio
import slo_core
import detectors

COUNTERS = (('e', 'EligibleCount'), ('b', 'AvailabilityBadCount'), ('l', 'LatencyBadCount'))


def handler(event, context):
    reg = cwio.registry()
    env, service = reg['env'], reg['metric_service']
    now = cwio.event_time(event)
    last = cwio.minute_index(now - timedelta(seconds=120))      # 마지막 완료 분
    stamp = now.replace(second=0, microsecond=0)                 # Flag 는 계산한 분으로 찍는다(경보가 최신 구간을 본다)
    src = boto3.client('cloudwatch', region_name=reg['api_region'])
    out = boto3.client('cloudwatch', region_name=reg['monitor_region'])
    ops = list(slo_core.OPERATIONS)

    queries = [cwio.stat(f'{c}{i}', 'RuleUp/SLI', name, {'Env': env, 'Service': service, 'Operation': op})
               for i, op in enumerate(ops) for c, name in COUNTERS]
    start = datetime.fromtimestamp((last - slo_core.LONGEST_WINDOW_MIN + 1) * 60, timezone.utc)
    end = datetime.fromtimestamp((last + 1) * 60, timezone.utc)
    raw = cwio.get_metric_data(src, queries, start, end)

    flags, diag = [], []
    for i, op in enumerate(ops):
        series = to_series(raw.get(f'e{i}', {}), raw.get(f'b{i}', {}), raw.get(f'l{i}', {}))
        results = slo_core.evaluate_operation(series, last)
        dims = {'Env': env, 'Service': service, 'Operation': op}
        for rule_id, (flag, why) in results.items():
            if flag is not None:
                flags.append(('AlarmFlag', {**dims, 'Rule': rule_id}, flag, stamp, 'None'))
        cwio.log('slo_eval', env=env, operation=op, last_minute=last, rules={k: v[1] for k, v in results.items()})
        if op in slo_core.BURN_DIAGNOSTIC_OPS:
            for minutes, br in slo_core.burn_diagnostics(series, last).items():
                diag.append(('BurnRate', {**dims, 'Window': f'{minutes}m'}, br, stamp, 'None'))

    cwio.put(out, 'RuleUp/SLO', flags + diag)
    if now.minute % 15 == 0:
        publish_budget(src, out, env, service, ops, now, stamp)
    if reg['rules'].get('detectors_enabled') and now.minute % 5 == 0:
        detectors.run(reg, src, out, now)
    cwio.put(out, 'RuleUp/Observe', [('CollectorHeartbeat', {'Env': env, 'Component': 'slo-calculator'}, 1, stamp, 'Count')])
    cwio.ping(reg['healthchecks'].get('obs04_ping_url'))
    return {'last_minute': last, 'flags': len(flags)}


def to_series(e, b, l):
    """세 카운터가 모두 있던 분만 「관측된 분」이다 — 하나라도 비면 그 분은 누락으로 본다."""
    return {m: (e[m], b[m], l[m]) for m in e if m in b and m in l}


def publish_budget(src, out, env, service, ops, now, stamp):
    last_hour = int((now - timedelta(seconds=120)).timestamp()) // 3600 - 1   # 마지막 완료 시간
    start = datetime.fromtimestamp((last_hour - 719) * 3600, timezone.utc)
    end = datetime.fromtimestamp((last_hour + 1) * 3600, timezone.utc)
    queries = [cwio.stat(f'{c}{i}', 'RuleUp/SLI', name, {'Env': env, 'Service': service, 'Operation': op}, period=3600)
               for i, op in enumerate(ops) for c, name in COUNTERS[:2]]
    raw = cwio.get_metric_data(src, queries, start, end)
    data = []
    for i, op in enumerate(ops):
        e, b = raw.get(f'e{i}', {}), raw.get(f'b{i}', {})
        bud = slo_core.budget({h: (e[h], b.get(h, 0)) for h in e}, last_hour)
        dims = {'Env': env, 'Service': service, 'Operation': op}
        if bud.remaining_pct is not None:
            data.append(('BudgetRemainingPct', dims, bud.remaining_pct, stamp, 'Percent'))
        data.append(('BudgetFreeze', dims, 1 if bud.freeze else 0, stamp, 'None'))
        cwio.log('budget', env=env, operation=op, eligible=bud.eligible, bad=bud.bad,
                 coverage=round(bud.coverage, 4), remaining_pct=bud.remaining_pct, enforceable=bud.enforceable)
    cwio.put(out, 'RuleUp/SLO', data)
