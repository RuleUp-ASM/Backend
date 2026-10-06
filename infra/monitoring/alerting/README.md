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
| `p0_p1.py` | 노션 3절 P0/P1 임계값의 원본 — 서버 수·ECS CPU/메모리·p95·5xx·RDS·DLQ·FCM 성공률·Redis 히트율, 옛 이름 경보 정리 |
| `alarms.py` | SQS 적체, 인증 배치·sync, Outbox, LLM 경보 |
| `dashboard.py [prod\|stg]` | 서버(운영) 대시보드 `ruleup-<env>-ops` — 로그·트레이스 바로가기, 최근 ERROR 로그 포함 |
| `business_dashboard.py` | 사용자 지표 대시보드 `ruleup-prod-business`(가입·로그인·인증 시도·인증 성공률) — prod 전용 |
| `p0_mention.py` | P0 → 긴급 채널 커스텀 알림(멘션)·복구 스레드, 긴급 채널 구성을 `ruleup-prod-p0-slack` 으로 |
| `slack_fallback.py` | Slack 전달 실패 감지 경보 → 이메일 대체 토픽 |

이메일은 Slack 이 실패했을 때만 온다. 대체 토픽 확인 링크를 누른 뒤 기존 Slack 토픽의 이메일 구독을 해지했다:
`aws sns unsubscribe --region ap-northeast-2 --subscription-arn <arn>` (`ruleup-prod-alerts`·`ruleup-stg-alerts`·삭제된 `ruleup-prod-alarms`).

앱 지표는 `CloudWatchMetricsConfig` 의 허용 목록만 `RuleUp/App/<prod|stg>` 로 들어온다(`biz.*` 는 prod 만 — stg 는 운영 지표만 본다). CloudWatch 이름은
카운터 `<이름>.count`, 게이지 `<이름>.value`, 타이머 `<이름>.count|sum|avg|max`(ms) 이다.
경보를 추가할 때는 기존 경보·토픽부터 조회해 중복을 만들지 않는다.

## P0 / P1 (노션 「RuleUp 모니터링」 3절)

경보 이름이 등급이다 — `ruleup-<env>-P0-*` 는 긴급 채널(멘션)까지, `ruleup-<env>-P1-*` 는 운영 채널만.
EventBridge 규칙이 `ruleup-prod-P0-` 접두어로 잡으므로 **새 P0 경보는 이름만 맞추면 멘션이 붙는다.**

| 등급 | 조건 | 경보 |
|---|---|---|
| P0 | 정상 서버 0대 1분¹ | `P0-api-no-healthy-target` |
| P0 | ECS CPU ≥95% 5분 / 메모리 ≥97% 5분 | `P0-ecs-cpu-critical` / `P0-ecs-memory-critical` |
| P0 | API p95 >3초 5분 | `P0-api-latency-p95-critical` |
| P0 | 인증 sync 5xx 5분 30건 | `P0-verification-sync-failed`(alarms.py, prod) |
| P0 | RDS 남은 공간 <5GB | `P0-rds-<id>-storage-low` |
| P0 | 서비스 전체 장애 — 요청 50% 이상 5xx(5분, 20건 이상) / ALB 5xx | `P0-api-5xx-ratio` / `P0-api-5xx-alb` |
| P1 | 정상 서버 1대로 감소(prod) | `P1-api-hosts-reduced` |
| P1 | ECS CPU ≥85% 10분 / 메모리 ≥90% 10분 | `P1-ecs-cpu-high` / `P1-ecs-memory-high` |
| P1 | API p95 >1.5초 10분 | `P1-api-latency-p95` |
| P1 | 인증 sync 5xx 5분 5건 | `P1-verification-sync-failed` |
| P1 | SQS 적체 / DLQ | `P1-sqs-<q>-backlog-age` / `P1-sqs-<q>-dlq` |
| P1 | FCM 성공률 <90%(15분, 5건 이상) · LLM 실패·지연 | `P1-fcm-success-rate` · `P1-llm-*` |
| P1 | Redis 캐시 히트율 급락² | `P1-redis-<node>-hit-rate-drop` |

¹ 노션은 30초지만 ALB HealthyHostCount 가 1분 해상도라 1분이 최소다.
² 고정값 대신 이상 탐지 밴드(평소 범위 아래로 15분). 학습에 2주쯤 걸려 그 전엔 넓게 잡힌다.
초기값은 타이트하게 두고 오탐을 보며 조정한다 — 바꿀 때는 노션과 `p0_p1.py` 를 같이 고친다.

**온콜**: Incident Manager(`ssm-contacts`·`ssm-incidents`)는 조직 SCP 가 막는다. 지금 P0 의 온콜 수단은
긴급 채널 멘션(`p0_mention.py`)뿐이다.
