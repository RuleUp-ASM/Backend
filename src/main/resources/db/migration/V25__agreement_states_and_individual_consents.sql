-- =====================================================================
-- V25: 동의 체계 정합 — 온보딩 테크 스펙 5-3 · 5-7 (2026-08-31 확정분)
--
--  바뀐 것 세 가지다.
--
--  1) 상태와 이력을 분리한다.
--     기존 user_agreements 는 append-only 이력이었고, 현재 상태를 알려면 타입별 최신 행을
--     정렬해 뽑아야 했다. 그런데 이 판정이 핫 경로다 — 위치·건강 인증 제출마다 개별 동의
--     여부를 확인해 403 AGREEMENT_REQUIRED 를 내려야 하기 때문이다. 이력이 쌓일수록
--     느려지는 구조라 현재 상태를 별도 테이블(유저당 최대 7행 고정)로 뽑는다.
--     이력은 그대로 남긴다 — 정보통신망법상 마케팅 수신 철회 이력이 남아야 한다.
--
--  2) NIGHT_PUSH 를 폐기한다 (2026-08-28).
--     야간 알림 수신 동의 약관이 사라졌다. 야간은 동의 여부와 무관하게 알림 분류별로
--     일괄 처리한다(알림 정책 §5). 남은 행은 근거가 사라진 동의이므로 지운다.
--
--  3) 법정 개별 동의 2종을 추가한다.
--     LOCATION_INFO · HEALTH_INFO. 위치기반 서비스 약관 동의만으로는 개인위치정보 동의를
--     대신할 수 없어 별도로 받아야 하는 항목이다(위치정보법·개인정보보호법).
--     가입 시점이 아니라 해당 인증 수단 최초 사용 시점에 받으므로 백필하지 않는다 —
--     행이 없는 것이 곧 "한 번도 동의한 적 없음"이다.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1) 이력 테이블 개명 + 타입 집합 교체
-- ---------------------------------------------------------------------
RENAME TABLE `user_agreements` TO `user_agreement_events`;

-- 폐지된 야간 동의 이력 제거. enum 에서 값을 빼기 전에 지워야 MODIFY 가 통과한다.
DELETE FROM `user_agreement_events` WHERE `agreement_type` = 'NIGHT_PUSH';

ALTER TABLE `user_agreement_events`
    MODIFY COLUMN `agreement_type`
        ENUM('TOS','PRIVACY','LOCATION','MARKETING','EVENT','LOCATION_INFO','HEALTH_INFO')
        NOT NULL COMMENT '약관 5종 + 법정 개별 동의 2종. 구 NIGHT_PUSH 폐기(2026-08-28)';

ALTER TABLE `user_agreement_events`
    COMMENT='동의·철회 이력(append-only) — 입증 책임의 근거';

-- ---------------------------------------------------------------------
-- 2) 현재 상태 테이블
-- ---------------------------------------------------------------------
CREATE TABLE `user_agreement_states` (
    `user_id`        BINARY(16) NOT NULL,
    `agreement_type` ENUM('TOS','PRIVACY','LOCATION','MARKETING','EVENT','LOCATION_INFO','HEALTH_INFO')
                     NOT NULL,
    -- 현재 값. 1=동의, 0=철회. 행 자체가 없으면 "한 번도 동의한 적 없음"이라 의미가 다르다.
    `agreed`         TINYINT(1) NOT NULL,
    -- 현재 동의한 버전 — 개정 재동의 판정이 이 값 하나로 끝난다.
    `version`        VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `agreed_at`      DATETIME(3) NOT NULL,
    -- PK 하나로 유저 단위 전체 조회와 단건 게이트 조회가 모두 끝난다. 유저당 최대 7행이라
    -- 보조 인덱스를 두지 않는다.
    PRIMARY KEY (`user_id`, `agreement_type`),
    CONSTRAINT `fk_user_agreement_states_user`
        FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
    CONSTRAINT `chk_user_agreement_states_agreed` CHECK (`agreed` IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='동의 현재 상태 — 유저당 최대 7행 고정, UPSERT 로 덮어씀';

-- ---------------------------------------------------------------------
-- 3) 이력에서 현재 상태 백필
--
--  타입별 최신 행(created_at DESC, id DESC)이 곧 현재 상태다. 같은 밀리초에 여러 행이
--  들어간 경우가 있어 created_at 만으로는 유일하지 않으므로 id 까지 tie-break 에 넣는다.
-- ---------------------------------------------------------------------
INSERT INTO `user_agreement_states` (`user_id`, `agreement_type`, `agreed`, `version`, `agreed_at`)
SELECT e.`user_id`, e.`agreement_type`, e.`agreed`, e.`version`, e.`created_at`
  FROM `user_agreement_events` e
  JOIN (
        SELECT `user_id`, `agreement_type`, MAX(CONCAT(
                   DATE_FORMAT(`created_at`, '%Y%m%d%H%i%s%f'), HEX(`id`))) AS `latest_key`
          FROM `user_agreement_events`
         GROUP BY `user_id`, `agreement_type`
       ) latest
    ON latest.`user_id` = e.`user_id`
   AND latest.`agreement_type` = e.`agreement_type`
   AND latest.`latest_key` = CONCAT(DATE_FORMAT(e.`created_at`, '%Y%m%d%H%i%s%f'), HEX(e.`id`));
