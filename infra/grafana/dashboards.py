"""prod 운영·서비스 대시보드를 Grafana Cloud 에 올린다(여러 번 돌려도 같다).

사용: python3 infra/grafana/dashboards.py
  주소·토큰은 alert_rules.py 와 같은 곳(~/.ruleup-migration)에서 읽는다. 지표 출처도 alert_rules.py 머리말과 같다.
"""
import json

from alert_rules import FOLDER, PROM, call

DS = {"type": "prometheus", "uid": PROM}
E = 'env="prod"'


class Board:
    def __init__(self, uid, title, tags):
        self.uid, self.title, self.tags, self.panels, self.x, self.y, self.n = uid, title, tags, [], 0, 0, 0

    def row(self, title):
        self.n += 1
        if self.x:
            self.y += 8
        self.panels.append({"id": self.n, "type": "row", "title": title, "collapsed": False, "gridPos": {"x": 0, "y": self.y, "w": 24, "h": 1}})
        self.x, self.y = 0, self.y + 1

    def ts(self, title, exprs, unit="short", w=8, h=8, stack=False, max_=None):
        self.n += 1
        if self.x + w > 24:
            self.x, self.y = 0, self.y + h
        defaults = {"unit": unit, "custom": {"lineWidth": 1, "fillOpacity": 10, "showPoints": "never", "spanNulls": 180000,
                                             "stacking": {"mode": "normal" if stack else "none"}}}
        if max_ is not None:
            defaults["max"], defaults["min"] = max_, 0
        self.panels.append({"id": self.n, "type": "timeseries", "title": title, "datasource": DS, "gridPos": {"x": self.x, "y": self.y, "w": w, "h": h},
                            "targets": [{"refId": chr(65 + i), "datasource": DS, "expr": e, "legendFormat": leg} for i, (e, leg) in enumerate(exprs)],
                            "fieldConfig": {"defaults": defaults, "overrides": []},
                            "options": {"legend": {"displayMode": "list", "placement": "bottom"}, "tooltip": {"mode": "multi"}}})
        self.x += w

    def stat(self, title, expr, unit="short", w=4, h=4):
        self.n += 1
        if self.x + w > 24:
            self.x, self.y = 0, self.y + h
        self.panels.append({"id": self.n, "type": "stat", "title": title, "datasource": DS, "gridPos": {"x": self.x, "y": self.y, "w": w, "h": h},
                            "targets": [{"refId": "A", "datasource": DS, "expr": expr, "instant": True}],
                            "fieldConfig": {"defaults": {"unit": unit}, "overrides": []},
                            "options": {"reduceOptions": {"calcs": ["lastNotNull"]}, "colorMode": "value", "graphMode": "area"}})
        self.x += w

    def push(self):
        dash = {"uid": self.uid, "title": self.title, "tags": self.tags, "timezone": "Asia/Seoul", "schemaVersion": 39, "refresh": "1m",
                "time": {"from": "now-6h", "to": "now"}, "panels": self.panels}
        r = call("POST", "/api/dashboards/db", {"dashboard": dash, "folderUid": FOLDER, "overwrite": True})
        print(self.uid, r.get("status"), r.get("url"), r.get("message") or "")


def ops():
    b = Board("ruleup-prod-ops", "RuleUp prod 운영", ["ruleup", "prod"])
    b.row("요청")
    b.ts("API 요청 수(분)", [(f"sum(ruleup_traffic_api_request_count{{{E}}})", "요청")])
    b.ts("실패율 %(5분, 기능별)", [(f"100 * sum by (operation)(sum_over_time(ruleup_sli_availability_bad_count{{{E}}}[5m])) / sum by (operation)(sum_over_time(ruleup_sli_eligible_count{{{E}}}[5m]))", "{{operation}}")], "percent")
    b.ts("응답 시간 p95(ms, 기능별)", [(f'max by (operation)(ruleup_sli_duration_ms{{{E},quantile="0.95"}})', "{{operation}}")], "ms")
    b.ts("거절 응답(4xx, 분)", [(f"sum by (status)(ruleup_traffic_http_reject_count{{{E}}})", "{{status}}")])
    b.ts("sync 수신 · 로그인 · 푸시 시도(분)", [(f"sum(ruleup_traffic_sync_received_count{{{E}}})", "sync"), (f"sum(ruleup_traffic_login_completed_count{{{E}}})", "로그인"),
                                             (f"sum(ruleup_traffic_push_attempt_count{{{E}}})", "푸시")])
    b.ts("외부 공급자 호출 · 실패(분)", [(f"sum by (feature, provider)(ruleup_ops_provider_call_count{{{E}}})", "{{feature}}/{{provider}} 호출"),
                                      (f"sum by (feature, provider)(ruleup_ops_provider_failure_count{{{E}}})", "{{feature}}/{{provider}} 실패")])
    b.row("서버 (EC2 한 대)")
    b.ts("CPU %", [('100 * (1 - avg(rate(node_cpu_seconds_total{mode="idle"}[2m])))', "사용")], "percent", max_=100)
    b.ts("메모리", [("node_memory_MemTotal_bytes - node_memory_MemAvailable_bytes", "사용"), ("node_memory_MemTotal_bytes", "전체"),
                   ("node_memory_SwapTotal_bytes - node_memory_SwapFree_bytes", "스왑 사용")], "bytes")
    b.ts("디스크 여유 %(/)", [('100 * node_filesystem_avail_bytes{mountpoint="/",fstype!="tmpfs"} / node_filesystem_size_bytes{mountpoint="/",fstype!="tmpfs"}', "여유")], "percent", max_=100)
    b.row("앱 (JVM · 풀)")
    b.ts("JVM 메모리(MB, 영역별)", [(f"sum by (area)(ruleup_ops_jvm_memory_used_mb{{{E}}})", "{{area}}")], "decmbytes", stack=True)
    b.ts("GC 직후 Old %", [(f"max(ruleup_ops_old_gen_after_gc_pct{{{E}}})", "old")], "percent", max_=100)
    b.ts("프로세스 CPU % · Tomcat 스레드 사용 %", [(f"max(ruleup_ops_process_cpu_pct{{{E}}})", "CPU"), (f"max(ruleup_ops_max_busy_thread_pct{{{E}}})", "스레드")], "percent")
    b.ts("DB 커넥션 풀", [(f"max(ruleup_ops_max_pool_active_pct{{{E}}})", "사용 %"), (f"max(ruleup_ops_max_pool_pending{{{E}}})", "대기 스레드")])
    b.ts("가동 시간(배포 시 0 으로)", [(f"ruleup_ops_process_uptime_sec{{{E}}}", "{{instance}}")], "s")
    b.ts("LLM 호출 실패(누적 증가/10분)", [(f"sum(increase(llm_call_failed_total{{{E}}}[10m]))", "결과 없음")])
    b.row("DB (RDS) · 큐")
    b.ts("RDS CPU %", [("aws_rds_cpuutilization_average", "CPU")], "percent", max_=100)
    b.ts("RDS 여유 메모리 · 스왑", [("aws_rds_freeable_memory_minimum", "여유 메모리"), ("aws_rds_swap_usage_maximum", "스왑 사용")], "bytes")
    b.ts("RDS 여유 저장공간", [("aws_rds_free_storage_space_minimum", "여유")], "bytes")
    b.ts("DB 접속 % · 최장 트랜잭션 · 락 대기(초)", [(f"max(ruleup_db_db_connection_pct{{{E}}})", "접속 %"), (f"max(ruleup_db_oldest_transaction_sec{{{E}}})", "최장 트랜잭션"),
                                              (f"max(ruleup_db_oldest_lock_wait_sec{{{E}}})", "락 대기")])
    b.ts("큐: 가장 오래된 메시지(초)", [("aws_sqs_approximate_age_of_oldest_message_maximum", "{{dimension_QueueName}}")], "s")
    b.ts("DLQ 메시지 · Outbox 미처리 나이", [("aws_sqs_approximate_number_of_messages_visible_maximum", "{{dimension_QueueName}}"),
                                         (f"max(outbox_pending_oldest_age_seconds{{{E}}})", "outbox 나이(초)")])
    b.push()


def business():
    b = Board("ruleup-prod-business", "RuleUp prod 서비스 지표", ["ruleup", "prod", "biz"])
    b.row("지금")
    b.stat("전체 회원", f"max(biz_members_total{{{E}}})")
    b.stat("오늘 인증", f"sum(biz_verification_today{{{E}}})")
    b.stat("가입(24시간)", f"sum(increase(biz_signup_total{{{E}}}[24h]))")
    b.stat("로그인(24시간)", f"sum(increase(biz_login_total{{{E}}}[24h]))")
    b.stat("챌린지 생성(24시간)", f"sum(increase(biz_challenge_created_total{{{E}}}[24h]))")
    b.stat("챌린지 참여(24시간)", f"sum(increase(biz_challenge_joined_total{{{E}}}[24h]))")
    b.row("흐름")
    b.ts("가입(시간당)", [(f"sum(increase(biz_signup_total{{{E}}}[1h]))", "가입")], w=12)
    b.ts("로그인(시간당)", [(f"sum(increase(biz_login_total{{{E}}}[1h]))", "로그인")], w=12)
    b.ts("챌린지 초안 · 생성 · 참여(시간당)", [(f"sum(increase(biz_challenge_draft_total{{{E}}}[1h]))", "초안"), (f"sum(increase(biz_challenge_created_total{{{E}}}[1h]))", "생성"),
                                           (f"sum(increase(biz_challenge_joined_total{{{E}}}[1h]))", "참여")], w=12)
    b.ts("인증 시도(시간당)", [(f"sum(increase(biz_verification_attempt_total{{{E}}}[1h]))", "시도")], w=12)
    b.row("회원 · 방")
    b.ts("전체 · 활동 회원", [(f"max(biz_members_total{{{E}}})", "전체"), (f"max by (window)(biz_members_active{{{E}}})", "활동 {{window}}")], w=12)
    b.ts("방 수", [(f"sum by (status, verification)(biz_rooms_count{{{E}}})", "{{status}} {{verification}}")], w=12)
    b.ts("자동 인증: 대상 · 판정된 사람", [(f"max by (state)(biz_auto_people{{{E}}})", "{{state}}"), (f"max(biz_auto_judged_people{{{E}}})", "판정됨")], w=12)
    b.ts("화면 조회자(오늘)", [(f'max by (screen)(biz_screen_viewers{{{E},window="today"}})', "{{screen}}")], w=12)
    b.push()


if __name__ == "__main__":
    ops()
    business()
