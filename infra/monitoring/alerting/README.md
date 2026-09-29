# 경보 전달 (Slack · 이메일)

노션 「RuleUp 모니터링 설계」의 AWS 쪽 구성. CLI 스크립트로 적용했다(IaC 아님). 모두 재실행해도 안전하다.
`deploy-monitoring.yml` 은 이 폴더를 실행하지 않는다 — IAM 관리자 자격으로 로컬에서 돌린다.

| 토픽 | 받는 곳 | 보내는 경보 |
|---|---|---|
| `ruleup-prod-p0-slack` | Slack `#ruleup-alert-urgent`(C0C4Z5VJJNN) — 이성은 멘션 | EventBridge 가 prod `P0-*` 상태 변경을 커스텀 알림으로 변환 |
| `ruleup-prod-p0-alerts` | 구독 없음(P0 경보의 AlarmActions 로만 남아 있음) | prod `P0-*` |
| `ruleup-prod-alerts` | Slack `#ruleup-alert`(C0C4HUAHBHV) | prod 전체(P0 포함) |
| `ruleup-stg-alerts` | Slack `#ruleup-alert` | stg 전체 |
| `ruleup-alert-fallback-email`(서울·us-east-1) | 이메일 | Slack 전달 실패 감지 경보 `ruleup-slack-fallback-*` 만 |

Slack 전달은 Amazon Q Developer in chat applications(워크스페이스 `ASM - LEE`, 역할 `ruleup-chatbot-notify` — CloudWatch 읽기 전용).

| 스크립트 | 하는 일 |
|---|---|
| `slack_channels.py` | P0 토픽·Amazon Q 역할·채널 구성 |
| `route_existing_alarms.py` | 기존 prod P0 경보를 긴급 토픽에도 연결, 모든 경보에 복구(OK) 알림, ECS 크래시 알림 형식 |
| `alarms.py` | SQS 적체·DLQ, 인증 배치·sync, Outbox, LLM 경보 |
| `dashboard.py` | 운영 대시보드 `ruleup-prod-ops` |
| `p0_mention.py` | P0 → 긴급 채널 커스텀 알림(멘션)·복구 스레드, 긴급 채널 구성을 `ruleup-prod-p0-slack` 으로 |
| `slack_fallback.py` | Slack 전달 실패 감지 경보 → 이메일 대체 토픽 |

이메일은 Slack 이 실패했을 때만 온다. 대체 토픽 확인 링크를 누른 뒤 기존 Slack 토픽의 이메일 구독을 해지했다:
`aws sns unsubscribe --region ap-northeast-2 --subscription-arn <arn>` (`ruleup-prod-alerts`·`ruleup-stg-alerts`·삭제된 `ruleup-prod-alarms`).

앱 지표는 `CloudWatchMetricsConfig` 의 허용 목록만 `RuleUp/App/<prod|stg>` 로 들어온다. CloudWatch 이름은
카운터 `<이름>.count`, 게이지 `<이름>.value`, 타이머 `<이름>.count|sum|avg|max`(ms) 이다.
경보를 추가할 때는 기존 경보·토픽부터 조회해 중복을 만들지 않는다.
