# 운영 공지 푸시 계약

2026-09-18 제품 결정 · QA NOTI-14

- 운영 공지(`ANNOUNCEMENT`)는 공지 탭에 적재하고 푸시 큐에도 넣는다.
- 마케팅 수신 동의나 계정·챌린지 그룹 토글로 운영 공지를 거르지 않는다.
- 푸시 마스터가 꺼져 있거나 공지를 이미 읽었으면 푸시를 보내지 않는다. 알림함 적재는 유지한다.
- 21:00 이상 08:00 미만 KST에는 다음 08:00까지 보류하고, 발송 직전에 설정과 읽음을 다시 검사한다.
- 같은 공지를 재처리해도 사용자별 알림과 푸시 큐 적재는 한 번이다.
- `MARKETING`은 기존과 같이 광고 수신 동의자에게만 적재·발송한다.
- 운영자 API의 성공 응답은 팬아웃 접수를 의미하며, 모든 기기에 푸시가 전달됐다는 뜻은 아니다.

검증: `NotificationPublishIT`, `NotificationDispatchDecisionTest`, `NotificationSqsQueueIT`.

## 푸시가 안 왔다는 제보를 볼 때 (QA NOTI-13 · NOTI-14 · WAT-11)

- **`pushed_at IS NULL` 은 발송 실패의 증거가 아니다.** 이 컬럼은 성공했고 <b>억제 대상 타입일 때만</b> 채운다(08:00 피크의 쓰기를 줄이려는 설계). 억제 구간이 없는 `ANNOUNCEMENT`·`CS_ANSWERED` 는 정상적으로 보내도 계속 NULL 이다. DB 로 판별하려면 억제 구간이 있는 타입(예: `PENALTY_FAILURE_SHARED`)의 행을 본다.
- 타입 레지스트리에서 접히는 일은 없다 — **23종 전부 `pushable=true`** 다. 공지도 큐에 들어간다.
- 실제로 꺼져 있는 곳은 둘이다. 기동 로그 한 줄(`푸시가 나가지 않는 상태다 …`)이 어느 쪽인지 말해 준다.
  1. `NOTIFICATION_QUEUE_URL` 이 비면 프로듀서·컨슈머가 배선되지 않는다.
  2. `FCM_ENABLED=false` 면 전송기가 스텁으로 떨어진다. 스텁은 `PUSH_DISABLED` 실패를 돌려주므로 `push.result` 로그가 SUCCESS 로 보이지 않는다.
- 둘 다 켜져 있는데 안 오면 `notification.push` 로거의 `suppressedReason` 을 본다 — `ALREADY_READ`(푸시 도착 전에 알림함을 열었다)·`MASTER_OFF`·`INTERVAL`(같은 대상 24시간)·`NO_DEVICE` 가 흔하다.
