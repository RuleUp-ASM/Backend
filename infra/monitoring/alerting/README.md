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
| `catalog.py` · `p0_p1.py <env>` | 노션 알람 카탈로그의 경보 정의(원본)·적용, 옛 이름 경보 정리. `tests_catalog.py` 가 등급·누락 정책·리전 계약을 시험 |
| `routing.py` | 중앙 감시 리전(us-east-1) 토픽·Slack 구성·P0 멘션 규칙 |
| `security_events.py` | SEC-02/03/04 EventBridge 규칙 |
| `dashboard.py [prod\|stg]` | 서버(운영) 대시보드 `ruleup-<env>-ops` — OPS-1~6 순서(OPS-6 JVM 런타임: 힙 영역·GC 정지·스레드·클래스·버퍼)(런북이 패널 번호로 가리킨다), 4xx 로그 표, 최근 ERROR 로그 |
| `observe_dashboard.py [prod\|stg]` | 감시망 대시보드 `ruleup-<env>-observe`(us-east-1) — OBS-1~4 |
| `business_dashboard.py` | 사용자 지표 대시보드 `ruleup-prod-business` — ① 로그인·가입 ② 챌린지 참여(초안 경로·생성·초안 수정률·탐색/초대 가입) ③ 인증(자동 vs 수동). 묶음마다 기간 합계·비율 숫자 칸. prod 전용 |
| `p0_mention.py` | P0 → 긴급 채널 커스텀 알림(멘션)·복구 스레드, 긴급 채널 구성을 `ruleup-prod-p0-slack` 으로 |
| `slack_fallback.py` | Slack 전달 실패 감지 경보 → 이메일 대체 토픽 |
| `night_hold.py <env>` | 야간 보류 큐(notifications)의 QUEUE-01 을 21:00 에 끄고 08:10 에 켠 뒤 08:11 에 OK 로 되돌리는 EventBridge Scheduler 일정·역할 |

이메일은 Slack 이 실패했을 때만 온다. 대체 토픽 확인 링크를 누른 뒤 기존 Slack 토픽의 이메일 구독을 해지했다:
`aws sns unsubscribe --region ap-northeast-2 --subscription-arn <arn>` (`ruleup-prod-alerts`·`ruleup-stg-alerts`·삭제된 `ruleup-prod-alarms`).

앱 지표는 `CloudWatchMetricsConfig` 의 허용 목록만 `RuleUp/App/<prod|stg>` 로 들어온다(`biz.*` 는 prod 만 — stg 는 운영 지표만 본다). CloudWatch 이름은
카운터 `<이름>.count`, 게이지 `<이름>.value`, 타이머 `<이름>.count|sum|avg|max`(ms) 이다.
경보를 추가할 때는 기존 경보·토픽부터 조회해 중복을 만들지 않는다.

## P0 / P1 (노션 「알람 카탈로그 · SLO 구현 계약」)

정의는 `catalog.py`, 적용은 `p0_p1.py <env>`, 상태표는 `../alarm-catalog.yaml`. 이름이 등급이다 —
`ruleup-<env>-P0-<ID>-*` 는 긴급 채널(멘션)까지, `ruleup-<env>-P1-<ID>-*` 는 운영 채널만.
EventBridge 규칙이 서울·버지니아 모두 `ruleup-prod-P0-` 접두어로 잡으므로 **새 P0 경보는 이름만 맞추면 멘션이 붙는다**
(EDGE-01 의 구성 요소 leaf 는 일부러 `ruleup-<env>-edge-leaf-*` 로 둔다).

| 등급 | ID | 조건(요약) | 리전 |
|---|---|---|---|
| P0 | SLO-01 / SLO-02 | BR(1h·5m) ≥14.4 / BR(6h·30m) ≥6, 짧은 창 표본·실패 게이트 | 버지니아 |
| P0 | API-01 | 2분 실패율 ≥50%·요청 ≥20·실패 ≥10 | 버지니아 |
| P0 | EDGE-01 | 오리건·버지니아 공개 점검 둘 다 30초 간격 2회 연속 실패 | 버지니아 |
| P0 | NATIVE-01 / NATIVE-02 | Route 53 1분 / ALB Target 5xx ≥50%(2분·요청 ≥20·오류 ≥10) | 버지니아 / 서울 |
| P0 | DB-10 / DB-11 | RDS CPU ≥99% 1분 / Freeable ≤RAM 5% 1분 + Swap 증가 (10/15 리뷰) | 서울 |
| P0 | OBS-04 | slo-calculator·resource-monitor 하트비트 5분 누락 (Healthchecks.io 전 대체) | 버지니아 |
| P0 | RES-01~04 (`res_enabled`) | 마지막 정상 태스크까지 하드 메모리 ≥99% · OOM 반복 + 정상 ≤1 · CPU 99% + 확장 불능 + 포화 · RDS 소진 임박 + 확장 불가 | 버지니아 |
| P1 | SLO-03/04 · LAT-01 · API-02 | 느린 소진 · 지연 BR ≥3 · 3분 실패율 ≥5% | 버지니아 |
| P1 | ECS-01~09 · JVM · POOL · THREAD | 카탈로그 §4·§5 (CPU 80% 3분/90% 1분/99%, 하드 메모리 80% 3분/90% 1분, OOM ≥1 즉시 …) | 서울 |
| P1 | DB-01~08 · REDIS · QUEUE · JOB · EXT · TOKEN | 카탈로그 §5·§6 | 서울 |
| P1 | SEC-01/05 · INV-01 · BACKUP-01~04 · OBS-01 | 카탈로그 §6.1·§7 | 서울·버지니아 |
| P1 | SEC-02/03/04 | EventBridge 규칙(`security_events.py`) | 서울·버지니아 |
| P1 | OBS-02 | SNS 전달 실패(`slack_fallback.py`) → 이메일 | 서울·버지니아 |

**예전 P0 는 내렸다**: ECS CPU 95%·메모리 97%·p95 3초·RDS 5GB·sync 5xx 30건·정상 서버 0대. 숫자 하나로 야간 호출하지 않는다 —
실제 장애는 SLO·API-01·EDGE·NATIVE 가, 임박한 고갈은 RES 가 잡는다.

**야간 보류 큐(QUEUE-01-notifications)**: 앱 알림 컨슈머는 21:00~08:00 KST 에 큐를 읽지 않는다(`NotificationWindow`). 밤에 들어온
메시지는 08:00 에 한꺼번에 나가는 게 설계라 >10분 조건이 매일 밤 울렸다(2026-10-20~). `night_hold.py` 가 Scheduler 로
21:00 `DisableAlarmActions` → 08:10 `EnableAlarmActions` → 08:11 `SetAlarmState OK`. 마지막 단계가 있어야 밤새 ALARM 이던 경보가
아침에 컨슈머가 정말 멈췄을 때 다시 울린다(그 경우 Slack 에 OK·ALARM 이 연달아 온다). `p0_p1.py` 를 밤에 돌려도 이 경보는 꺼 둔 채로 둔다.
DLQ(QUEUE-02)·moderation 큐는 밤에도 그대로 감시한다.

누락 정책: 커스텀 P0·EDGE leaf = `ignore`(입력이 끊겨도 정상으로 돌아가지 않는다 — 재무장은 정상 Flag=0 또는 `../lambdas/rearm.py`),
하트비트·NATIVE-01 = `breaching`, 나머지 = `notBreaching`.

**온콜**(`pagerduty.py`, prod 만): **1차 담당(이성은)에게 먼저 → 10분 안에 ACK 가 없으면 팀원 → 그래도 없으면 처음부터 다시(2회)**.
PagerDuty 서비스 「RuleUp prod P0」 — 긴급도 항상 high, ACK 후 15분 안에 해결 안 되면 다시 울림, 시간 기반 자동 종료 끔.
CloudWatch → 같은 리전 SNS `ruleup-prod-pagerduty`(서울·버지니아) → PagerDuty 공식 CloudWatch 연동. 경보가 OK 로 돌아오면 사건이 닫힌다.
Incident Manager(`ssm-contacts`·`ssm-incidents`)는 조직 SCP 가 막아 쓰지 않는다. 긴급 채널 멘션(`p0_mention.py`)은 보조로 그대로 둔다.

개통 순서(PagerDuty 계정·API 키가 생긴 뒤):
1. 두 사람 모두 PagerDuty 앱 설치 + 프로필 알림 규칙에 **High-urgency: 즉시 푸시 + 1분 뒤 전화**를 넣는다(서비스가 아니라 사람마다 설정이다).
2. `PAGERDUTY_API_TOKEN=… python3 infra/monitoring/alerting/pagerduty.py setup --primary <내 PD 이메일> --secondary <팀원 PD 이메일>` — 원장 `env/prod.json` 이 채워진다 → 커밋.
3. `python3 infra/monitoring/alerting/p0_p1.py prod` — P0 경보 AlarmActions·OKActions 에 PagerDuty 토픽이 붙는다.
4. 실수신 시험(G01): `pagerduty.py test-trigger` — 시험 경보 `ruleup-prod-P0-ONCALL-TEST` 를 실제 P0 와 같은 경로로 ALARM 으로 바꾼다 → Slack #ruleup-alert·긴급 채널 멘션·PagerDuty 가 함께 오는지, 내 폰을 **ACK 하지 않고** 10분 뒤 팀원 폰이 울리는지 → `pagerduty.py test-resolve`(Slack 복구 알림·사건 종료).
   노션 「대시보드·알림 요약표」 P0 온콜 연결 체크표 12종은 경보별로 `aws cloudwatch set-alarm-state` 로 한 번씩 확인한다(다음 평가에서 OK 로 돌아온다).
