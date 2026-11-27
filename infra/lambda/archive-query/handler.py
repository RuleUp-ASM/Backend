"""기존 계정 CloudWatch 지표 보관분(S3 Parquet)을 Grafana(Infinity 데이터 소스)에서 조회하는 창구.

GET <함수 URL>?ns=RuleUp/SLI&metric=RequestCount&stat=sum&from=<epoch ms>&to=<epoch ms>[&dims=<부분 문자열>][&period=60|300|3600][&region=…]
  · 헤더 x-api-key 가 환경변수 API_KEY 와 같아야 한다.
  · period 를 생략하면 구간 길이로 고른다(2일 이하 60초 · 45일 이하 5분 · 그 밖 1시간). 보관 해상도는 내보낸 날 기준
    1분 15일 · 5분 63일 · 1시간 455일이다. 구간이 길면 계열당 약 700점이 되도록 시각을 묶어 집계한다.
  · list=namespaces | metrics(ns 필요) 로 목록을 본다.
응답: [{"time": "2026-11-01T00:00:00Z", "series": "<dims JSON>", "value": 1.0}, …]
"""
import hmac
import json
import os
import re
import time

import boto3

ATHENA = boto3.client("athena")
WORKGROUP = os.environ.get("WORKGROUP", "ruleup-archive")
TABLE = "ruleup_archive.cw_metrics"
SAFE = re.compile(r"^[A-Za-z0-9_/.:\- =,\"{}%]*$")
# 여러 원본 시각을 한 칸으로 묶을 때의 집계식 — 응답이 함수 URL 한도(6MB)를 넘지 않게 계열당 점 수를 줄인다.
STATS = {"sum": 'sum("sum")', "cnt": "sum(cnt)", "min": 'min("min")', "max": 'max("max")',
         "avg": 'CASE WHEN sum(cnt) > 0 THEN sum("sum") / sum(cnt) END'}
POINTS = 700        # 계열 하나가 한 번에 돌려주는 점 수의 목표
MAX_ROWS = 30000


def _resp(code, body):
    return {"statusCode": code, "headers": {"Content-Type": "application/json"}, "body": json.dumps(body, ensure_ascii=False)}


def _safe(v):
    if not SAFE.match(v) or "--" in v:
        raise ValueError("허용하지 않는 문자")
    return v.replace("'", "")


def _query(sql):
    qid = ATHENA.start_query_execution(QueryString=sql, WorkGroup=WORKGROUP)["QueryExecutionId"]
    deadline = time.time() + 25
    while time.time() < deadline:
        st = ATHENA.get_query_execution(QueryExecutionId=qid)["QueryExecution"]["Status"]
        if st["State"] == "SUCCEEDED":
            break
        if st["State"] in ("FAILED", "CANCELLED"):
            raise RuntimeError(st.get("StateChangeReason", st["State"]))
        time.sleep(0.4)
    else:
        raise RuntimeError("조회 시간 초과")
    rows, first = [], True
    for page in ATHENA.get_paginator("get_query_results").paginate(QueryExecutionId=qid, PaginationConfig={"PageSize": 1000}):
        for r in page["ResultSet"]["Rows"]:
            if first:
                first = False
                continue
            rows.append([c.get("VarCharValue") for c in r["Data"]])
    return rows


def handler(event, _context):
    headers = {k.lower(): v for k, v in (event.get("headers") or {}).items()}
    if not hmac.compare_digest(headers.get("x-api-key", ""), os.environ["API_KEY"]):
        return _resp(401, {"error": "unauthorized"})
    p = event.get("queryStringParameters") or {}
    try:
        region = _safe(p.get("region", "ap-northeast-2"))
        if p.get("list") == "namespaces":
            rows = _query(f"SELECT DISTINCT region, namespace FROM {TABLE} WHERE period = 3600 ORDER BY 1, 2")
            return _resp(200, [{"region": r[0], "namespace": r[1]} for r in rows])
        ns = _safe(p["ns"])
        if p.get("list") == "metrics":
            rows = _query(f"SELECT metric, count(DISTINCT dims) FROM {TABLE} WHERE period = 3600 AND region = '{region}' "
                          f"AND namespace = '{ns}' GROUP BY 1 ORDER BY 1")
            return _resp(200, [{"metric": r[0], "series": int(r[1])} for r in rows])
        metric, stat = _safe(p["metric"]), STATS[p.get("stat", "sum")]
        t0, t1 = int(p["from"]) // 1000, int(p["to"]) // 1000
        span = t1 - t0
        period = int(p["period"]) if p.get("period") else (60 if span <= 2 * 86400 else 300 if span <= 45 * 86400 else 3600)
        if period not in (60, 300, 3600):
            raise ValueError("period")
        where = (f"period = {period} AND region = '{region}' AND namespace = '{ns}' AND metric = '{metric}' "
                 f"AND ts BETWEEN from_unixtime({t0}) AND from_unixtime({t1})")
        if p.get("dims"):
            where += f" AND dims LIKE '%{_safe(p['dims'])}%'"
        bucket = max(period, -(-span // POINTS // period) * period)
        rows = _query(f"SELECT floor(to_unixtime(ts) / {bucket}) * {bucket}, dims, {stat} FROM {TABLE} WHERE {where} "
                      f"GROUP BY 1, 2 ORDER BY 1 LIMIT {MAX_ROWS}")
        out = [{"time": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(float(r[0]))), "series": r[1], "value": float(r[2])}
               for r in rows if r[2] is not None]
        return _resp(200, out)
    except (KeyError, ValueError) as e:
        return _resp(400, {"error": f"잘못된 요청: {e}"})
    except RuntimeError as e:
        return _resp(502, {"error": str(e)})
