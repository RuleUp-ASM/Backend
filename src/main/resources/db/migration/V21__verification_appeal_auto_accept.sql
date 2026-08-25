-- 인증 이의 자동 인용(인증 정책 §5, 2026-08-25).
--
-- 구 모델의 Objection 은 "제출 → 방장/공동 관리자 승인·기각"을 전제로 status·decidedBy·decisionReason 을
-- 들고 있었다. 신정책에서 이의는 판정하지 않는다 — 형식 요건을 통과하면 즉시 인용되고, 통과하지 못하면
-- 접수 자체가 되지 않는다. 그래서 상태도 처리자도 없는 테이블로 갈아탄다.
--
-- 멱등 앵커도 (멤버, 날짜)에서 verificationDailyId 로 옮긴다. API 가 인증 ID 로 들어오고,
-- "실패 결과 하나에 이의 하나"가 정정·점수 중복 적용을 막는 실제 경계이기 때문이다.

CREATE TABLE `verification_appeals` (
    `id`                  binary(16)   NOT NULL,
    `verificationDailyId` binary(16)   NOT NULL COMMENT '이의 대상 인증(= API verificationId). 실패 결과 기준 멱등 앵커',
    `challengeId`         binary(16)   NOT NULL,
    `challengeMemberId`   binary(16)   NOT NULL,
    `userId`              binary(16)   NOT NULL COMMENT '신청자(= 인증 당사자)',
    `targetDate`          date         NOT NULL COMMENT '이의 대상 귀속일(KST)',
    `reason`              varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL
                          COMMENT '이의 사유. 10자 이상만 접수된다. 내용의 진위는 판단하지 않는다',
    `imageUrl`            varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL
                          COMMENT '증빙 사진(선택). 저장만 하고 판단에 쓰지 않는다 — 이상탐지의 동일 이미지 반복 입력',
    `acceptedAt`          datetime(6)  NOT NULL COMMENT '인용 시각. 접수 = 인용이라 접수 시각과 같다',
    `createdAt`           datetime(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uq_verification_appeals_verification` (`verificationDailyId`),
    KEY `idx_verification_appeals_user_accepted` (`userId`, `acceptedAt`),
    CONSTRAINT `fk_verification_appeals_verification`
        FOREIGN KEY (`verificationDailyId`) REFERENCES `VerificationDaily` (`id`),
    CONSTRAINT `fk_verification_appeals_user` FOREIGN KEY (`userId`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='인증 이의. 접수된 행은 전부 인용된 이의다 — 기각 상태가 존재하지 않는다';

-- 구 이의 이력 이관 --------------------------------------------------------------
--  · APPROVED(승인)만 신모델의 "인용된 이의"에 해당한다.
--  · PENDING/REJECTED 는 신정책에 대응하는 상태가 없다. 옮기면 "인용됐다"는 뜻이 돼 사실과 달라지므로
--    옮기지 않는다(구 테이블에 그대로 남아 있다가 아래에서 함께 정리된다).
--  · 같은 인증에 여러 건이 있었다면 uq 에 걸리므로 가장 먼저 승인된 한 건만 옮긴다.
INSERT INTO `verification_appeals`
    (`id`, `verificationDailyId`, `challengeId`, `challengeMemberId`, `userId`, `targetDate`,
     `reason`, `imageUrl`, `acceptedAt`, `createdAt`)
SELECT o.`id`, d.`id`, o.`challengeId`, o.`challengeMemberId`, o.`userId`, o.`targetDate`,
       o.`content`, o.`imageUrl`, COALESCE(o.`decidedAt`, o.`createdAt`), o.`createdAt`
FROM `Objection` o
         JOIN `VerificationDaily` d
              ON d.`challengeMemberId` = o.`challengeMemberId` AND d.`targetDate` = o.`targetDate`
WHERE o.`status` = 'APPROVED'
  AND o.`decidedAt` = (SELECT MIN(o2.`decidedAt`) FROM `Objection` o2
                       WHERE o2.`challengeMemberId` = o.`challengeMemberId`
                         AND o2.`targetDate` = o.`targetDate`
                         AND o2.`status` = 'APPROVED');

DROP TABLE `Objection`;
