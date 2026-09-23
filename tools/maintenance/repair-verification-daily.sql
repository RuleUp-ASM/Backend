-- mysql 클라이언트에서 SOURCE 후 CALL repair_verification_daily('UUID', 현재_version).
-- UTC 세션에서 실행한다. 원본 보존 + 버전 대조 + 단일 행 잠금으로 재실행/동시 수정에 대비한다.
-- 시각 없는 FAILED만 PENDING으로 되돌린다. 확정 시각을 추정해서 채우지 않는다.
-- 다음 확정 배치가 신호 재평가, 점수 새 버전, 감시자 outbox, 결과 알림을 정상 경로로 처리한다.
DELIMITER //
CREATE PROCEDURE repair_verification_daily(IN verification_uuid CHAR(36), IN expected_version BIGINT)
BEGIN
    DECLARE found_version BIGINT DEFAULT NULL;
    DECLARE found_date DATE;
    DECLARE bad_failure BOOLEAN;
    DECLARE bad_deadline BOOLEAN;
    DECLARE verification_id BINARY(16);
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL;
    END;

    IF verification_uuid IS NULL OR NOT IS_UUID(verification_uuid) OR expected_version IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'UUID and expected_version are required';
    END IF;
    SET verification_id = UUID_TO_BIN(verification_uuid);
    START TRANSACTION;
    SELECT version, targetDate,
           status = 'FAILED' AND (verifiedAt IS NULL OR shareableAt IS NULL
             OR verifiedAt < TIMESTAMP(DATE_ADD(targetDate, INTERVAL 1 DAY), '15:00:00')
             OR shareableAt < verifiedAt),
           finalizeAfter IS NULL
             OR finalizeAfter <> TIMESTAMP(DATE_ADD(targetDate, INTERVAL 1 DAY), '15:00:00')
             OR (appealClosesAt IS NOT NULL
               AND appealClosesAt <> TIMESTAMP(DATE_ADD(targetDate, INTERVAL 1 DAY), '15:00:00'))
      INTO found_version, found_date, bad_failure, bad_deadline
      FROM VerificationDaily WHERE id = verification_id FOR UPDATE;

    IF found_version IS NULL OR found_version <> expected_version THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Verification not found or version changed';
    END IF;
    IF NOT bad_failure AND NOT bad_deadline THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Verification does not need repair';
    END IF;
    IF bad_failure AND UTC_TIMESTAMP(6) < TIMESTAMP(DATE_ADD(found_date, INTERVAL 1 DAY), '15:00:00') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Wait until the original grace period ends';
    END IF;
    IF bad_failure AND NOT EXISTS (
        SELECT 1 FROM VerificationDaily d
          JOIN challenges c ON c.id = d.challengeId
          JOIN challenge_members m ON m.id = d.challengeMemberId AND m.challenge_id = c.id
         WHERE d.id = verification_id AND m.user_id = d.userId
           AND d.targetDate >= c.start_date AND (c.end_date IS NULL OR d.targetDate <= c.end_date)
           AND JSON_UNQUOTE(JSON_EXTRACT(c.verification_config, '$.selectedMethod')) = 'AUTO'
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Automatic challenge and member are required for replay';
    END IF;
    IF bad_failure AND EXISTS (SELECT 1 FROM verification_appeals WHERE verificationDailyId = verification_id) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Accepted appeal requires separate investigation';
    END IF;
    IF bad_failure AND (
        EXISTS (SELECT 1 FROM watcher_notices n WHERE n.verification_id = verification_id)
        OR EXISTS (SELECT 1 FROM outbox_messages WHERE dedup_key = CONCAT('ROUTINE_FAILURE_CONFIRMED:', LOWER(verification_uuid)))
        OR EXISTS (SELECT 1 FROM notifications n JOIN VerificationDaily d ON d.id = verification_id
            WHERE n.dedup_key = CONCAT('VERIFICATION_RESULT:', LOWER(BIN_TO_UUID(d.userId)), ':', LOWER(verification_uuid)))
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Existing result delivery requires separate investigation';
    END IF;

    INSERT INTO verification_integrity_repairs (verification_id, original_version, snapshot)
    SELECT id, version, JSON_OBJECT(
        'id', BIN_TO_UUID(id), 'challengeMemberId', BIN_TO_UUID(challengeMemberId),
        'challengeId', BIN_TO_UUID(challengeId), 'userId', BIN_TO_UUID(userId),
        'targetDate', targetDate, 'status', status, 'method', method,
        'failureReason', failureReason, 'gapReason', gapReason,
        'windowClosesAt', windowClosesAt, 'finalizeAfter', finalizeAfter,
        'finalizeRetryAt', finalizeRetryAt, 'appealClosesAt', appealClosesAt,
        'verifiedAt', verifiedAt, 'verifiedVia', verifiedVia, 'shareableAt', shareableAt,
        'acknowledgedAt', acknowledgedAt, 'version', version, 'createdAt', createdAt, 'updatedAt', updatedAt,
        'failureDetail', (SELECT JSON_OBJECT('reasonCode', f.reasonCode, 'evidenceSummary', f.evidenceSummary,
            'expectedValue', f.expectedValue, 'actualValue', f.actualValue, 'createdAt', f.createdAt)
            FROM verification_failure_details f WHERE f.verificationDailyId = verification_id))
    FROM VerificationDaily WHERE id = verification_id;

    IF bad_failure THEN
        DELETE FROM verification_failure_details WHERE verificationDailyId = verification_id;
    END IF;

    UPDATE VerificationDaily SET
        status = IF(bad_failure, 'PENDING', status),
        windowClosesAt = IF(bad_failure, NULL, windowClosesAt),
        verifiedAt = IF(bad_failure, NULL, verifiedAt),
        verifiedVia = IF(bad_failure, NULL, verifiedVia),
        shareableAt = IF(bad_failure, NULL, shareableAt),
        acknowledgedAt = IF(bad_failure, NULL, acknowledgedAt),
        finalizeAfter = TIMESTAMP(DATE_ADD(targetDate, INTERVAL 1 DAY), '15:00:00'),
        appealClosesAt = IF(status = 'SUCCESS', NULL,
                            TIMESTAMP(DATE_ADD(targetDate, INTERVAL 1 DAY), '15:00:00')),
        finalizeRetryAt = NULL,
        version = version + 1
    WHERE id = verification_id;
    COMMIT;
END//
DELIMITER ;
