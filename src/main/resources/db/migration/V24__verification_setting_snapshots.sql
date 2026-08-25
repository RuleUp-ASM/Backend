-- 멤버 인증 설정의 시점 스냅샷(2026-08-25).
--
-- 확정이 귀속일 이틀 뒤로 밀리면서 과거 날짜를 다시 평가하는 창이 하루 생겼다. 그런데 앵커(challenge_members.anchors)와
-- 대상 앱(screen_apps)은 현재 값만 들고 있어서, 유예 구간에 장소를 바꾸면 어제 판정이 새 장소 기준으로 돌아간다 —
-- 어제 갔던 곳이 갑자기 "안 간 곳"이 된다. 변경이 월 1회라 드물지만, 일어나면 그 사람의 판정이 조용히 뒤집힌다.
--
-- effectiveFrom 은 그 설정이 판정에 쓰이기 시작하는 KST 날짜다. 날짜 D 의 판정은 effectiveFrom <= D 중
-- 가장 늦은 스냅샷을 쓴다. append-only 이며 덮어쓰지 않는다 — 기준값 조정 후 과거 재판정의 근거이기도 하다.

CREATE TABLE `verification_setting_snapshots` (
    `id`                binary(16)  NOT NULL,
    `challengeMemberId` binary(16)  NOT NULL,
    `kind`              enum('ANCHORS','SCREEN_APPS') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
    `effectiveFrom`     date        NOT NULL COMMENT '이 설정이 판정에 쓰이기 시작하는 KST 날짜',
    `payload`           json        NOT NULL COMMENT '설정 값 원본(GeoAnchor[] 또는 ScreenApp[])',
    `createdAt`         datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    KEY `idx_setting_snapshots_lookup` (`challengeMemberId`, `kind`, `effectiveFrom`),
    CONSTRAINT `fk_setting_snapshots_member`
        FOREIGN KEY (`challengeMemberId`) REFERENCES `challenge_members` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='멤버 인증 설정의 시점 스냅샷. 과거 날짜는 그 날 적용되던 설정으로 평가한다';

-- 기존 멤버 백필 ----------------------------------------------------------------
--  현재 값을 "챌린지 시작일부터 적용된 설정"으로 심는다. 실제 설정 시점은 알 수 없지만,
--  그 이전 날짜는 어차피 인증 대상이 아니라 판정에 영향이 없다.
--  백필이 없어도 조회가 현재 값으로 폴백하므로 안전망이 이중으로 걸린다.
INSERT INTO `verification_setting_snapshots` (`id`, `challengeMemberId`, `kind`, `effectiveFrom`, `payload`)
SELECT UNHEX(REPLACE(UUID(), '-', '')), m.`id`, 'ANCHORS', c.`start_date`, m.`anchors`
FROM `challenge_members` m
         JOIN `challenges` c ON c.`id` = m.`challenge_id`
WHERE m.`anchors` IS NOT NULL;

INSERT INTO `verification_setting_snapshots` (`id`, `challengeMemberId`, `kind`, `effectiveFrom`, `payload`)
SELECT UNHEX(REPLACE(UUID(), '-', '')), m.`id`, 'SCREEN_APPS', c.`start_date`, m.`screen_apps`
FROM `challenge_members` m
         JOIN `challenges` c ON c.`id` = m.`challenge_id`
WHERE m.`screen_apps` IS NOT NULL;
