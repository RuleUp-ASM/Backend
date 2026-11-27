"""prod 경보 규칙을 Grafana Cloud 에 올린다 — 기존 CloudWatch 카탈로그(infra/monitoring/alerting/catalog.py)의 ID·임계값을 옮긴 것.

사용: python3 infra/grafana/alert_rules.py [--dry-run]
  Grafana 주소·토큰은 ~/.ruleup-migration/grafana.stack-url · grafana.sa-token 에서 읽는다(저장소에 두지 않는다).
  폴더 RuleUp(uid ruleup)의 그룹 ruleup-p0 · ruleup-p1 을 통째로 바꾼다 — 여러 번 돌려도 같다. 공개 경로 점검(그룹 edge)은 건드리지 않는다.

지표 출처
  ruleup_* · biz_* · outbox_* · verification_* · llm_*   앱이 OTLP 로 보낸다(분 단위 값은 그 분의 합·최댓값이 게이지 한 점).
  node_*                                              서버의 Alloy(호스트 지표)
  aws_rds_* · aws_sqs_*                               Alloy 가 CloudWatch 에서 가져온다(infra/ec2/config.alloy)
라우팅: env=prod 는 Slack #ruleup-alert, severity=P0 는 PagerDuty·긴급 채널에도 간다.

기존 카탈로그에서 옮기지 않은 것: ECS·ALB·Redis(ElastiCache) 자원 경보(그 자원이 없다), 이상 탐지(FLOW·HTTP-04·CACHE), 백업(BACKUP),
LLM 지연(EXT-02), 폴백 공급자 haiku(새 계정에서 Bedrock 이 막혀 있어 항상 실패한다).
"""
import json
import os
import subprocess
import sys

D = os.path.expanduser("~/.ruleup-migration/")
URL = open(D + "grafana.stack-url").read().strip()
TOKEN = open(D + "grafana.sa-token").read().strip()
PROM = "grafanacloud-prom"
FOLDER = "ruleup"
DRY = "--dry-run" in sys.argv


def call(method, path, body=None):
    cmd = ["curl", "-s", "-X", method, URL + path, "-H", "Authorization: Bearer " + TOKEN, "-H", "Content-Type: application/json",
           "-H", "X-Disable-Provenance: true"]
    if body is not None:
        cmd += ["--data", json.dumps(body)]
    out = subprocess.run(cmd, capture_output=True, text=True, check=True).stdout
    return json.loads(out) if out.strip() else {}


E = 'env="prod"'
RAM = 1024 ** 3          # db.t4g.micro
DISK = 20 * 1024 ** 3    # RDS 할당 20GB
RDS = 'dimension_DBInstanceIdentifier="ruleup-prod-mysql"'


def sot(metric, window, sel=E, by=""):
    """분 단위 게이지의 창 합계."""
    grp = f" by ({by})" if by else ""
    return f"sum{grp}(sum_over_time({metric}{{{sel}}}[{window}]))"


def burn(kind, window):
    """버짓 소진 속도. 가용성 목표 99.5%, 지연 목표 95%."""
    bad = "ruleup_sli_availability_bad_count" if kind == "avail" else "ruleup_sli_latency_bad_count"
    budget = 0.005 if kind == "avail" else 0.05
    return f"({sot(bad, window)} / {sot('ruleup_sli_eligible_count', window)} / {budget})"


def ratio(bad, total, window, by, sel_bad=E, sel_total=E, min_total=20, min_bad=0, pct=1):
    e = (f"(100 * {sot(bad, window, sel_bad, by)} / {sot(total, window, sel_total, by)} >= {pct})"
         f" and ({sot(total, window, sel_total, by)} >= {min_total})")
    if min_bad:
        e += f" and ({sot(bad, window, sel_bad, by)} >= {min_bad})"
    return e


# (ID, 접미, 등급, for, PromQL — 결과가 있으면 위반, 요약)
RULES = [
    # ---------- P0 ----------
    ("SLO-01", "", "P0", "1m", f"({burn('avail', '1h')} >= 14.4) and ({burn('avail', '5m')} >= 14.4) and ({sot('ruleup_sli_eligible_count', '1h')} >= 300)",
     "가용성 버짓 급속 소진 — 1시간·5분 소진 속도 모두 14.4배 이상"),
    ("SLO-02", "", "P0", "1m", f"({burn('avail', '6h')} >= 6) and ({burn('avail', '30m')} >= 6) and ({sot('ruleup_sli_eligible_count', '6h')} >= 1000)",
     "가용성 버짓 소진 — 6시간·30분 소진 속도 모두 6배 이상"),
    ("API-01", "", "P0", "0s", ratio("ruleup_sli_availability_bad_count", "ruleup_sli_eligible_count", "2m", "operation", min_bad=10, pct=50),
     "급성 장애 — 2분 실패율 50% 이상(요청 20건·실패 10건 이상)"),
    ("OBS-04", "", "P0", "0s", f"absent_over_time(ruleup_ops_process_uptime_sec{{{E}}}[5m])",
     "앱 지표 5분 누락 — 앱이 멈췄거나 지표 전송이 끊겼다. 다른 경보가 판정을 못 한다"),
    ("RES-01", "", "P0", "2m", "(node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes < 0.03) and (node_memory_SwapFree_bytes / node_memory_SwapTotal_bytes < 0.5)",
     "서버 메모리 고갈 임박 — 여유 3% 미만 + 스왑 절반 이상 사용"),
    ("RES-04", "", "P0", "2m", 'node_filesystem_avail_bytes{mountpoint="/",fstype!="tmpfs"} / node_filesystem_size_bytes{mountpoint="/",fstype!="tmpfs"} < 0.05',
     "서버 디스크 여유 5% 미만 — 가득 차면 컨테이너와 로그가 멈춘다"),
    ("DB-10", "", "P0", "1m", f"aws_rds_cpuutilization_average{{{RDS}}} >= 99", "RDS CPU 99% 이상 — DB 포화로 핵심 API 가 곧 멈춘다"),
    ("DB-11", "", "P0", "1m", f"(aws_rds_freeable_memory_minimum{{{RDS}}} <= {int(RAM * 0.05)}) and (delta(aws_rds_swap_usage_maximum{{{RDS}}}[5m]) > 0)",
     "RDS 여유 메모리 5% 이하 + 스왑 증가 — DB 메모리 고갈 임박"),
    # ---------- P1 ----------
    ("SLO-03", "", "P1", "1m", f"({burn('avail', '24h')} >= 3) and ({burn('avail', '2h')} >= 3) and ({sot('ruleup_sli_eligible_count', '24h')} >= 1000)",
     "느린 버짓 소진 — 24시간·2시간 소진 속도 3배 이상"),
    ("SLO-04", "", "P1", "1m", f"({burn('avail', '72h')} >= 1) and ({burn('avail', '6h')} >= 1) and ({sot('ruleup_sli_eligible_count', '72h')} >= 1000)",
     "느린 버짓 소진 — 72시간·6시간 소진 속도 1배 이상"),
    ("LAT-01", "", "P1", "1m", f"({burn('lat', '1h')} >= 3) and ({burn('lat', '5m')} >= 3) and ({sot('ruleup_sli_eligible_count', '1h')} >= 300)",
     "지연 버짓 소진 — 1시간·5분 지연 소진 속도 3배 이상"),
    ("API-02", "", "P1", "0s", ratio("ruleup_sli_availability_bad_count", "ruleup_sli_eligible_count", "3m", "operation", pct=5), "API 실패율 5% 이상(3분·요청 20건 이상)"),
    ("OBS-01", "app", "P1", "0s", f"absent_over_time(ruleup_ops_process_uptime_sec{{{E}}}[3m])", "앱 지표 3분 누락 — 관측 누락이지 장애 확정은 아니다"),
    ("OBS-01", "db-collector", "P1", "0s", f'absent_over_time(ruleup_observe_collector_heartbeat{{{E},component="db-collector"}}[3m])', "DB 진단 수집 3분 누락(권한 부족 포함)"),
    ("OBS-01", "host", "P1", "0s", "absent_over_time(node_load1[5m])", "서버 지표 5분 누락 — Alloy 가 멈췄을 수 있다"),
    ("HOST-01", "80", "P1", "3m", '100 * (1 - avg(rate(node_cpu_seconds_total{mode="idle"}[2m]))) >= 80', "서버 CPU 80% 이상 3분"),
    ("HOST-01", "99", "P1", "1m", '100 * (1 - avg(rate(node_cpu_seconds_total{mode="idle"}[1m]))) >= 99', "서버 CPU 99% 이상 1분 — 긴급"),
    ("HOST-02", "", "P1", "5m", "node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes < 0.10", "서버 메모리 여유 10% 미만 5분(배포 중 2분 안팎은 정상)"),
    ("HOST-03", "", "P1", "5m", 'node_filesystem_avail_bytes{mountpoint="/",fstype!="tmpfs"} / node_filesystem_size_bytes{mountpoint="/",fstype!="tmpfs"} < 0.15', "서버 디스크 여유 15% 미만"),
    ("JVM-01", "", "P1", "5m", f"max(ruleup_ops_old_gen_after_gc_pct{{{E}}}) >= 80", "GC 직후 Old 영역 80% 이상 5분"),
    ("POOL-01", "", "P1", "3m", f"max(ruleup_ops_max_pool_active_pct{{{E}}}) >= 80", "DB 커넥션 풀 사용 80% 이상 3분"),
    ("POOL-02", "", "P1", "2m", f"max(ruleup_ops_max_pool_pending{{{E}}}) >= 1", "DB 커넥션을 기다리는 스레드가 2분째 있다"),
    ("POOL-03", "", "P1", "0s", f"{sot('ruleup_ops_pool_timeout_delta', '2m')} >= 1", "DB 커넥션 획득 timeout 발생"),
    ("THREAD-01", "", "P1", "5m", f"max(ruleup_ops_max_busy_thread_pct{{{E}}}) >= 80", "Tomcat 스레드 사용 80% 이상 5분"),
    ("DB-01", "80", "P1", "5m", f"aws_rds_cpuutilization_average{{{RDS}}} >= 80", "RDS CPU 80% 이상 5분"),
    ("DB-01", "90", "P1", "1m", f"aws_rds_cpuutilization_average{{{RDS}}} >= 90", "RDS CPU 90% 이상 1분"),
    ("DB-02", "", "P1", "5m", f"(aws_rds_freeable_memory_minimum{{{RDS}}} <= {int(RAM * 0.10)}) and (delta(aws_rds_swap_usage_maximum{{{RDS}}}[10m]) > 0)", "RDS 여유 메모리 10% 이하 5분 + 스왑 증가"),
    ("DB-03", "75", "P1", "5m", f"max(ruleup_db_db_connection_pct{{{E}}}) >= 75", "DB 접속 수가 최대의 75% 이상 5분"),
    ("DB-03", "95", "P1", "1m", f"max(ruleup_db_db_connection_pct{{{E}}}) >= 95", "DB 접속 수가 최대의 95% 이상"),
    ("DB-05", "", "P1", "5m", f"aws_rds_free_storage_space_minimum{{{RDS}}} / {DISK} < 0.15", "RDS 여유 저장공간 15% 미만"),
    ("DB-06", "", "P1", "3m", f"max(ruleup_db_oldest_lock_wait_sec{{{E}}}) >= 5", "최장 락 대기 5초 이상 3분"),
    ("DB-07", "", "P1", "0s", f"{sot('ruleup_db_deadlock_delta', '5m')} >= 3", "데드락 3건 이상/5분"),
    ("DB-08", "", "P1", "5m", f"max(ruleup_db_oldest_transaction_sec{{{E}}}) >= 60", "최장 트랜잭션 60초 이상 5분"),
    ("QUEUE-01", "moderation", "P1", "1m", 'aws_sqs_approximate_age_of_oldest_message_maximum{dimension_QueueName="ruleup-prod-moderation"} > 600', "검수 큐의 가장 오래된 메시지가 10분 넘음 — 소비자 정지·오류"),
    # 알림 큐는 21:00~08:00 KST 에 일부러 받지 않는다(NotificationWindow) — 08:10~21:00 KST(23:10~12:00 UTC)에만 본다.
    ("QUEUE-01", "notifications", "P1", "1m", '(aws_sqs_approximate_age_of_oldest_message_maximum{dimension_QueueName="ruleup-prod-notifications"} > 600) and on() ((hour() < 12) or (hour() == 23 and minute() >= 10))',
     "알림 큐의 가장 오래된 메시지가 10분 넘음(주간) — 소비자 정지·오류"),
    ("QUEUE-02", "", "P1", "1m", 'aws_sqs_approximate_number_of_messages_visible_maximum{dimension_QueueName=~"ruleup-prod-.*-dlq"} >= 1', "재시도 끝에 실패한 메시지가 DLQ 에 있다"),
    ("JOB-01", "outbox", "P1", "1m", f"max(outbox_pending_oldest_age_seconds{{{E}}}) > 300", "Outbox 의 가장 오래된 미처리 작업이 5분 넘음"),
    ("JOB-01", "outbox-dead-lettered", "P1", "0s", f"max(delta(outbox_dead_lettered_count{{{E}}}[5m])) > 0", "Outbox 작업이 재시도 끝에 포기됨"),
    ("JOB-02", "finalize-overdue", "P1", "5m", f"max(verification_finalize_overdue{{{E}}}) > 0", "확정 시각이 1시간 넘게 지난 미확정 판정이 5분째 — 확정 배치 정지·반복 실패"),
    ("JOB-02", "finalize-failed", "P1", "0s", f"sum(increase(verification_finalize_failed_total{{{E}}}[5m])) >= 1", "판정 확정이 건별로 실패해 격리됨"),
    ("JOB-02", "materialize-failed", "P1", "0s", f"sum(increase(verification_materialize_failed_total{{{E}}}[5m])) >= 1", "무신호 귀속일 채우기 실패"),
    ("EXT-01", "", "P1", "0s", ratio("ruleup_ops_provider_failure_count", "ruleup_ops_provider_call_count", "5m", "feature, provider",
                                      sel_bad=E + ',provider!="haiku"', sel_total=E + ',provider!="haiku"', pct=10),
     "외부 공급자(Gemini·FCM) 기술 실패 10% 이상(5분·호출 20건 이상)"),
    ("EXT-03", "", "P1", "0s", f"sum(increase(llm_call_failed_total{{{E}}}[10m])) >= 5", "LLM 결과 없이 끝난 호출 5건 이상/10분 — 사용자는 기본 템플릿을 받는다(Bedrock 폴백이 막혀 있다)"),
    ("TOKEN-01", "", "P1", "0s", f"(100 * {sot('ruleup_ops_invalid_token_count', '5m', E + ',provider=\"fcm\"')} / {sot('ruleup_ops_provider_call_count', '5m', E + ',feature=\"push\",provider=\"fcm\"')} >= 20)"
                                  f" and ({sot('ruleup_ops_provider_call_count', '5m', E + ',feature=\"push\",provider=\"fcm\"')} >= 20)", "무효 FCM 토큰 20% 이상(5분·발송 20건 이상)"),
    ("SEC-01", "", "P1", "0s", f"{sot('ruleup_security_admin_login_failed_count', '10m', by='service')} >= 5", "관리자 로그인 실패 5회 이상/10분"),
    ("SEC-05", "", "P1", "0s", f"{sot('ruleup_security_refresh_reuse_detected_count', '5m')} >= 1", "refresh 토큰 재사용 감지(7일 유예 밖)"),
    ("INV-01", "", "P1", "0s", f"{sot('ruleup_ops_invariant_violation_count', '5m')} >= 1", "점수 원장 재구성 불일치"),
]


def rule(rid, suffix, sev, hold, expr, summary):
    uid = ("ruleup-" + rid + ("-" + suffix if suffix else "")).lower()
    return {"uid": uid, "title": f"{rid}{'-' + suffix if suffix else ''} {summary.split(' — ')[0]}"[:180], "ruleGroup": f"ruleup-{sev.lower()}",
            "folderUID": FOLDER, "condition": "C", "for": hold, "noDataState": "OK", "execErrState": "Error", "orgID": 1,
            "labels": {"catalog": rid, "env": "prod", "severity": sev}, "annotations": {"summary": summary, "description": f"`{expr}`"[:1900]},
            "data": [{"refId": "A", "relativeTimeRange": {"from": 600, "to": 0}, "datasourceUid": PROM,
                      "model": {"expr": expr, "instant": True, "refId": "A", "intervalMs": 1000, "maxDataPoints": 43200}},
                     {"refId": "C", "relativeTimeRange": {"from": 0, "to": 0}, "datasourceUid": "__expr__",
                      "model": {"type": "threshold", "expression": "A", "refId": "C",
                                "conditions": [{"evaluator": {"type": "gt", "params": [-1e18]}, "operator": {"type": "and"},
                                                "query": {"params": ["C"]}, "reducer": {"params": [], "type": "last"}, "type": "query"}]}}]}


if __name__ == "__main__":
    for sev in ("P0", "P1"):
        rules = [rule(*r) for r in RULES if r[2] == sev]
        group = f"ruleup-{sev.lower()}"
        if DRY:
            print(group, len(rules))
            continue
        r = call("PUT", f"/api/v1/provisioning/folder/{FOLDER}/rule-groups/{group}", {"title": group, "folderUid": FOLDER, "interval": 60, "rules": rules})
        print(group, len(r.get("rules", [])) if isinstance(r, dict) and "rules" in r else r)
