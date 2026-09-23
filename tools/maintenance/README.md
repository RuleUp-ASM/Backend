# 인증 이상 행 복구

V13 배포 후 사용한다. 스케줄러의 재시도는 `finalizeRetryAt`으로 분리되며, JPA 저장 시 정책 기한과 실패 확정 시각을 검사한다. SQL 직접 수정은 JPA 검사를 우회하므로 운영 DB 쓰기는 별도로 통제한다.

`repair-verification-daily.sql`은 프로시저를 정의하며 `CALL` 전에는 판정 데이터를 변경하지 않는다. 점검한 **한 UUID와 조회한 version**만 받는다. 원본은 `verification_integrity_repairs.snapshot`에 남기고, 버전이 바뀌었거나 정상 행이면 중단한다. 실행 중 오류는 해당 건 전체를 롤백한다.

```sql
SET time_zone = '+00:00';
SELECT BIN_TO_UUID(id), version, status, targetDate, finalizeAfter,
       appealClosesAt, verifiedAt, shareableAt
FROM VerificationDaily WHERE id = UUID_TO_BIN('확인한 UUID');

SOURCE tools/maintenance/repair-verification-daily.sql;
CALL repair_verification_daily('확인한 UUID', 조회한_version);
DROP PROCEDURE repair_verification_daily;
```

- 기한만 어긋난 행은 귀속일 D+2 00:00 KST로 복구한다. 확정 결과는 유지한다.
- 확정·공유 시각이 없거나 조기 확정된 FAILED는 PENDING으로 돌려 정상 확정 배치에 재투입한다. 지난 시각을 추측해서 채우지 않는다. 원래 유예 종료 이후에만 허용하고, 판정 가능한 자동 챌린지·멤버가 있어야 한다. 인용된 이의나 기존 결과/감시자 통지가 있으면 별도 조사하도록 중단한다. 이미 발송된 내용을 덮어쓰거나 정상 멱등 키로 교정 통지가 누락되는 일을 피한다.
- 다음 배치가 원본 신호/저장 근거로 재평가하고 새 버전의 점수 입력, 실패 상세, 감시자 outbox와 결과 알림을 생성한다. 복구 직후 PENDING은 완료가 아니다. 처리 실패 시 재시도 시각과 배치 오류를 확인한다. 이미 반영된 점수는 새 버전 처리가 끝나야 정정된다.
- `VerificationIntegrityCheck.check()`의 세 카운트, 해당 판정의 최종 시각, `score-input:<UUID>:<version>` 및 `ROUTINE_FAILURE_CONFIRMED:<UUID>` outbox 처리를 확인한다. 성공으로 재판정되면 실패 감시자 outbox는 생성되지 않는다.

이 도구는 신규 배포 시 기존 행을 자동으로 바꾸지 않는다. 원인 조사를 위해 보관한 스냅샷은 복구 확인 전 삭제하지 않는다.

# sync 진단

`diagnose-sync.sql` 은 **읽기 전용**이다. 「신호가 안 들어온 것」과 「들어왔는데 판정에 안 쓰인 것」을 가른다. 위에서 아래로 여섯 단계이고, 앞 단계가 0을 내면 그 자리가 끊긴 자리라 뒤는 볼 필요가 없다.

```sql
SET time_zone = '+00:00';
SET @user := UUID_TO_BIN('유저 UUID');
SOURCE tools/maintenance/diagnose-sync.sql;
```

- 요청이 닿는지(`verification_sync_sessions.lastSeenAt`) → 동의가 있는지(`user_agreement_states`) → 신호가 쌓이는지(`verification_*_signals`) → 배제됐는지(`excludeReason`) → 멤버가 평가 대상인지(`setup_status`) → 판정이 움직였는지(`VerificationDaily` · `VerificationMethodResult.evidence`) 순이다.
- 위치·건강은 **개별 동의가 없으면 적재 이전에 버린다**. 응답 `consentRequired` 에 그 사실이 실려 내려가므로, 동의가 비어 있으면 서버가 아니라 동의 흐름을 먼저 본다.
- `excludeReason` 이 `UNTRUSTED_SOURCE` 로 차 있으면 `users.device_id` 와 요청의 `deviceId` 를 대조한다. 기기를 바꾼 뒤 예전 기기가 백로그를 흘린 경우가 여기 걸린다.
- 신호는 쌓였는데 `VerificationMethodResult.evidence` 가 비어 있으면 원본이 평가기에 닿지 않은 것이다 — 귀속일(`observedDate`)과 판정일(`targetDate`)이 어긋났는지부터 본다.
- **시각이 두 무리로 갈린다.** JPA 가 쓰는 표는 UTC, `JdbcTemplate` 가 쓰는 표(`verification_*_signals` · `verification_sync_sessions` · `signal_exclusions`)는 KST 로 저장된다. 같은 sync 요청의 `receivedAt`(11:25:18)과 `lastEvaluatedAt`(02:25:18)이 9시간 차이로 보이는 이유이고, 실제 UTC 는 뒤쪽이다(서버 로그와 일치). 애플리케이션은 같은 드라이버로 되읽어 판정이 어긋나지 않지만, **SQL 안에서 두 무리의 시각을 직접 빼면 9시간이 틀린다.**
- `notifications.created_at` 은 이 갈림의 예외였다. 적는 쪽은 `JdbcTemplate`(KST) 인데 읽는 쪽은 JPA 의 `Instant`(UTC) 라 되읽어도 상쇄되지 않고 알림함 `createdAt` 이 9시간 미래로 나갔다(QA NOTI-13·NOTI-14·WAT-11). 적재를 UTC 벽시계로 고쳤고 기존 행은 `V14` 가 되돌렸다 — 이 표의 시각은 이제 UTC 무리다.
