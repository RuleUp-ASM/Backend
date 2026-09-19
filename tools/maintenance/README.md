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
