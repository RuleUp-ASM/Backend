"""감시 Lambda 공통 — 원장 읽기·GetMetricData 묶음 조회·PutMetricData 묶음 전송·구조화 로그. boto3 는 Lambda 런타임에 있다."""
import json
import os
from datetime import datetime, timezone

_HERE = os.path.dirname(os.path.abspath(__file__))


def registry():
    """패키지에 함께 묶인 원장(env/<env>.json → registry.json). 로컬 시험은 RULEUP_REGISTRY 로 경로를 준다."""
    path = os.environ.get('RULEUP_REGISTRY') or os.path.join(_HERE, 'registry.json')
    with open(path, encoding='utf-8') as f:
        return json.load(f)


def event_time(event):
    """Scheduler 가 실은 예정 시각 — 재시도·지연 실행도 같은 분을 계산하게(멱등)."""
    raw = (event or {}).get('time') or (event or {}).get('scheduled_time')
    if raw:
        return datetime.fromisoformat(raw.replace('Z', '+00:00')).astimezone(timezone.utc)
    return datetime.now(timezone.utc)


def minute_index(dt):
    return int(dt.timestamp()) // 60


def get_metric_data(cw, queries, start, end, chunk=15):
    """{Id: {분 인덱스(또는 period 인덱스): 값}} — 15개씩 나눠 묻고 NextToken 을 따라간다.
    값이 없는 시각은 키가 없다(FILL 하지 않는다)."""
    out = {}
    for i in range(0, len(queries), chunk):
        part = queries[i:i + chunk]
        token = None
        while True:
            kw = dict(MetricDataQueries=part, StartTime=start, EndTime=end, ScanBy='TimestampAscending')
            if token:
                kw['NextToken'] = token
            resp = cw.get_metric_data(**kw)
            for r in resp['MetricDataResults']:
                period = next(q for q in part if q['Id'] == r['Id']).get('MetricStat', {}).get('Period', 60)
                series = out.setdefault(r['Id'], {})
                for ts, v in zip(r['Timestamps'], r['Values']):
                    series[int(ts.timestamp()) // period] = v
            token = resp.get('NextToken')
            if not token:
                break
    return out


def stat(qid, namespace, name, dims, stat_name='Sum', period=60):
    return {'Id': qid, 'ReturnData': True, 'MetricStat': {
        'Metric': {'Namespace': namespace, 'MetricName': name,
                   'Dimensions': [{'Name': k, 'Value': v} for k, v in dims.items()]},
        'Period': period, 'Stat': stat_name}}


def put(cw, namespace, data):
    """data: [(이름, 차원 dict, 값, 시각, 단위)] — 20개씩."""
    datums = [{'MetricName': n, 'Dimensions': [{'Name': k, 'Value': v} for k, v in sorted(d.items())],
               'Value': float(v), 'Timestamp': ts, 'Unit': unit, 'StorageResolution': 60}
              for n, d, v, ts, unit in data]
    for i in range(0, len(datums), 500):
        cw.put_metric_data(Namespace=namespace, MetricData=datums[i:i + 500])


def log(kind, **fields):
    """CloudWatch Logs 에 한 줄 JSON — 창별 근거·보류 사유를 여기서 조회한다."""
    print(json.dumps({'kind': kind, **fields}, ensure_ascii=False, default=str))


def ping(url):
    """외부 watchdog(Healthchecks.io) — 원장에 URL 이 없으면 건너뛴다. 실패해도 본 계산을 멈추지 않는다."""
    if not url:
        return
    import urllib.request
    try:
        urllib.request.urlopen(url, timeout=5).read()
    except Exception as e:  # noqa: BLE001
        log('watchdog_ping_failed', error=str(e))
