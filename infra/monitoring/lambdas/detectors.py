"""이상 탐지 Flag — 노션 「알람 카탈로그」 §6 FLOW-01~03·HTTP-04·CACHE-01. SLO 계산기가 5분마다 부른다.

CloudWatch ANOMALY_DETECTION_BAND(m, 2) 를 기준선으로 쓰고, 게이트(FLOW 예상 하단 ≥5건/5분, HTTP 실제 ≥10건/5분,
CACHE 조회 ≥100건/5분)를 여기서 확인한 뒤 RuleUp/Detectors AlarmFlag(Env, Detector) 를 낸다.
밴드가 아직 없거나(학습 중) 입력이 비면 Flag 를 내지 않고 사유를 로그에 남긴다 — 「학습 부족」을 0 으로 숨기지 않는다.
경보는 Maximum ≥1, 300초·3/3 이라 세 구간 연속일 때만 운영 Slack(P1)으로 간다.
"""
from datetime import timedelta

import cwio

TRAFFIC = 'RuleUp/Traffic'
OPS = 'RuleUp/Ops'

# (Detector, 지표 이름, 추가 차원, 방향, 게이트)
DETECTORS = (
    ('FLOW-01', 'LoginCompletedCount', {}, 'lower', 5),
    ('FLOW-02', 'SyncReceivedCount', {}, 'lower', 5),
    ('FLOW-03', 'PushAttemptCount', {}, 'lower', 5),
    ('HTTP-04-400', 'HttpRejectCount', {'Status': '400'}, 'upper', 10),
    ('HTTP-04-401', 'HttpRejectCount', {'Status': '401'}, 'upper', 10),
    ('HTTP-04-403', 'HttpRejectCount', {'Status': '403'}, 'upper', 10),
    ('HTTP-04-429', 'HttpRejectCount', {'Status': '429'}, 'upper', 10),
)
CACHE = ('CACHE-01', 'explore', 100)


def decide(direction, value, lower, upper, gate):
    """1·0·None. FLOW 하한은 「예상 하단」이 게이트 이상일 때만(야간 기준선이 0 근처면 판정하지 않는다)."""
    if value is None or lower is None or upper is None:
        return None
    if direction == 'lower':
        if lower < gate:
            return None
        return 1 if value < lower else 0
    if value < gate:
        return 0
    return 1 if value > upper else 0


def run(reg, src, out, now):
    env, service = reg['env'], reg['metric_service']
    base = {'Env': env, 'Service': service}
    end = now.replace(second=0, microsecond=0) - timedelta(seconds=120)
    end = end - timedelta(minutes=end.minute % 5)                # 마지막 완료 5분 구간의 끝
    start = end - timedelta(minutes=5)
    stamp = now.replace(second=0, microsecond=0)
    flags = []
    for detector, name, extra, direction, gate in DETECTORS:
        value, lower, upper = band(src, TRAFFIC, name, {**base, **extra}, start, end)
        flag = decide(direction, value, lower, upper, gate)
        cwio.log('detector', env=env, detector=detector, value=value, lower=lower, upper=upper,
                 state='pending' if flag is None else ('breach' if flag else 'ok'))
        if flag is not None:
            flags.append(('AlarmFlag', {'Env': env, 'Detector': detector}, flag, stamp, 'None'))
    detector, kind, min_lookups = CACHE
    ratio, lower, upper, lookups = cache_band(src, {**base, 'CacheKind': kind}, start, end)
    flag = None if lookups is None or lookups < min_lookups else decide('lower', ratio, lower, upper, 0)
    cwio.log('detector', env=env, detector=detector, value=ratio, lower=lower, lookups=lookups,
             state='pending' if flag is None else ('breach' if flag else 'ok'))
    if flag is not None:
        flags.append(('AlarmFlag', {'Env': env, 'Detector': detector}, flag, stamp, 'None'))
    cwio.put(out, 'RuleUp/Detectors', flags)
    cwio.put(out, 'RuleUp/Observe', [('CollectorHeartbeat', {'Env': env, 'Component': 'anomaly-detector'}, 1, stamp, 'Count')])


def band(cw, namespace, name, dims, start, end):
    q = [cwio.stat('m', namespace, name, dims, 'Sum', 300),
         {'Id': 'band', 'Expression': 'ANOMALY_DETECTION_BAND(m, 2)', 'ReturnData': True}]
    return _read_band(cw.get_metric_data(MetricDataQueries=q, StartTime=start, EndTime=end), 'm')[:3]


def cache_band(cw, dims, start, end):
    q = [cwio.stat('hit', OPS, 'CacheHitCount', dims, 'Sum', 300) | {'ReturnData': False},
         cwio.stat('look', OPS, 'CacheLookupCount', dims, 'Sum', 300),
         {'Id': 'm', 'Expression': 'IF(look > 0, 100 * FILL(hit, 0) / look)', 'ReturnData': True},
         {'Id': 'band', 'Expression': 'ANOMALY_DETECTION_BAND(m, 2)', 'ReturnData': True}]
    resp = cw.get_metric_data(MetricDataQueries=q, StartTime=start, EndTime=end)
    value, lower, upper = _read_band(resp, 'm')
    lookups = next((r['Values'][-1] for r in resp['MetricDataResults'] if r['Id'] == 'look' and r['Values']), None)
    return value, lower, upper, lookups


def _read_band(resp, value_id):
    """밴드 식은 상·하한 두 시계열로 돌아온다(라벨 끝이 Upper/Lower). 모양이 다르면 None — stg 에서 먼저 확인한다."""
    value = lower = upper = None
    for r in resp['MetricDataResults']:
        if not r['Values']:
            continue
        v = r['Values'][-1]
        label = (r.get('Label') or '').lower()
        if r['Id'] == value_id:
            value = v
        elif r['Id'] == 'band' and 'upper' in label:
            upper = v
        elif r['Id'] == 'band' and 'lower' in label:
            lower = v
    return value, lower, upper
