# 경보 전달 (Slack · 이메일)

노션 「RuleUp 모니터링 설계」의 AWS 쪽 구성. CLI 스크립트로 적용했다(IaC 아님). 모두 재실행해도 안전하다.
`deploy-monitoring.yml` 은 이 폴더를 실행하지 않는다 — IAM 관리자 자격으로 로컬에서 돌린다.

| 토픽 | 받는 곳 | 보내는 경보 |
|---|---|---|
| `ruleup-prod-p0-alerts` | Slack 긴급 채널(C0C4HUAHBHV) | prod `P0-*` |
| `ruleup-prod-alerts` | 이메일 + Slack 운영 채널(C0C4Z5VJJNN) | prod 전체(P0 포함) |
| `ruleup-stg-alerts` | 이메일 + Slack 운영 채널 | stg 전체 |

Slack 전달은 Amazon Q Developer in chat applications(워크스페이스 `ASM - LEE`, 역할 `ruleup-chatbot-notify` — CloudWatch 읽기 전용).

| 스크립트 | 하는 일 |
|---|---|
| `slack_channels.py` | P0 토픽·Amazon Q 역할·채널 구성 |
| `route_existing_alarms.py` | 기존 prod P0 경보를 긴급 토픽에도 연결, 모든 경보에 복구(OK) 알림, ECS 크래시 알림 형식 |
| `alarms.py` | SQS 적체·DLQ, 인증 배치·sync, Outbox, LLM 경보 |
| `dashboard.py` | 운영 대시보드 `ruleup-prod-ops` |

앱 지표는 `CloudWatchMetricsConfig` 의 허용 목록만 `RuleUp/App/<prod|stg>` 로 들어온다. CloudWatch 이름은
카운터 `<이름>.count`, 게이지 `<이름>.value`, 타이머 `<이름>.count|sum|avg|max`(ms) 이다.
경보를 추가할 때는 기존 경보·토픽부터 조회해 중복을 만들지 않는다.
