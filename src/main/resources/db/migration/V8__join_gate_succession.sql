-- V8 — 가입 게이트 5중 검사 + 방장 승계(봇방장) 계약
--
--  1) user_challenge_counters — 동시 참여 3개 검사의 "사용자 행 락" 대상.
--     챌린지 행만 잠그면 서로 다른 두 방에 동시 가입할 때 각자 다른 행을 잡아
--     둘 다 "현재 2개"로 읽어 제한이 뚫린다(백엔드 테크스펙 4-3 P0).
--  2) challenges.owner_grant_reason — 3일 면책 판정용 승계 경위(정책 §11.3).
--     봇방장 전환·CLAIM(선착순) = 그 시점부터 3일간 재류 멤버 전원 면책 /
--     TRANSFER(직접 위임) = 면책 없음(일반 탈퇴 감점).
--
--  재입장 대기는 자진 탈퇴 1주 고정, 강퇴는 사유와 무관하게 1주→2주→4주 배수다(정책 §10.2).
--  영구 차단 플래그는 두지 않는다 — rejoin_available_at 하나로 전부 표현된다.

CREATE TABLE `user_challenge_counters` (
    `user_id`           binary(16) NOT NULL,
    `active_join_count` int        NOT NULL DEFAULT 0 COMMENT '현재 ACTIVE 참여 수 — 동시 3개 게이트의 락 대상',
    PRIMARY KEY (`user_id`),
    CONSTRAINT `fk_user_challenge_counters_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 기존 계정 백필: 지금 ACTIVE 멤버십 수를 그대로 옮긴다(회원 생성 시 0으로 함께 생성 — 애플리케이션).
INSERT INTO `user_challenge_counters` (`user_id`, `active_join_count`)
SELECT u.`id`, COALESCE(m.`cnt`, 0)
FROM `users` u
         LEFT JOIN (SELECT `user_id`, COUNT(*) AS `cnt`
                    FROM `challenge_members`
                    WHERE `status` = 'ACTIVE'
                    GROUP BY `user_id`) m ON m.`user_id` = u.`id`;

ALTER TABLE `challenges`
    ADD COLUMN `owner_grant_reason` varchar(10) NULL
        COMMENT '방장이 된 경위 — CREATE/TRANSFER/CLAIM. CLAIM 만 3일 면책 대상' AFTER `owner_granted_at`;

-- 기존 방은 생성자가 그대로 방장이므로 CREATE 로 표기(면책 대상 아님).
UPDATE `challenges` SET `owner_grant_reason` = 'CREATE' WHERE `owner_grant_reason` IS NULL;
