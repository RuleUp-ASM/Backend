# SIG-02 — GPS 정상 대조군과 출처 검증

## 2026-09-18 스테이징 DB 확인

QA의 `qa6-A_false`, `qa6-A-retry`, `qa6-P-ctrl`, `qa6-Q-omit`,
`qa6-P-cov`, `qa6-Q-cov` 위치 신호 6건은 모두 `UNTRUSTED_SOURCE`였다.
앞의 4건은 에뮬레이터 이름을, 뒤의 2건은 `users.installation_id`를
sync의 `deviceId`로 보냈다. 모두 해당 계정의 `users.device_id`와 달랐다.
따라서 이 요청들은 `isMock=false` 정상 대조군으로 사용할 수 없다.
DB를 직접 수정하거나 활성 기기 검증을 완화하지 않는다.

## 재검증 계약

1. 로그인/기기 등록에 사용한 **deviceInfo.deviceId**를 sync의 `deviceId`로 보낸다.
   설치 UUID(`installationId`) 및 기기 표시 이름과 구분한다.
2. 위치 정보 동의를 마친 READY 멤버의 바인딩 장소에서, 바인딩 적용 시각 이후의
   측위를 사용한다. 목표 체류 시간보다 긴 구간을 연속 포인트로 구성한다.
3. 서로 분리한 계정/챌린지로 다음 대조군을 실행한다. 이미 성공한 날짜에 모의
   위치를 추가해도 성공이 취소되지는 않으므로 이를 음성 대조군으로 쓰지 않는다.

| deviceId | 각 포인트의 isMock | 기대 |
|---|---|---|
| 활성 기기 ID | false | 체류 시간 충족 시 SUCCESS |
| 활성 기기 ID | true | 체류 0, PENDING, evidence.excludedMock 증가 |
| 활성 기기 ID | 생략/null | 체류 0, PENDING, evidence.excludedMock 증가 |
| installationId 또는 임의 값 | false | 원본 excludeReason=UNTRUSTED_SOURCE, 체류 0 |

HTTP 200은 동기화 요청 수신을 의미하며 인증 성공을 의미하지 않는다.
미확정 daily의 `method`는 null일 수 있으므로 DB 검증은
`VerificationMethodResult.verificationDailyId = VerificationDaily.id`로 연결한다.

`VerificationStrictDeviceIT`는 위 5가지 요청을 실제 HTTP 역직렬화·MySQL 저장·
재조회·평가 경로에서 확인한다. `EvaluatorAccuracyTest`는 평가기의 모의 위치와
누락 필드 제외를 추가로 확인한다. 이 테스트 통과와 스테이징 실기기 통과는 별도다.
