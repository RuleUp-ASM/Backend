-- =====================================================================
-- V10: challenge_stats — 탐색 조회용 파생 통계(Projection)
--
--  완주율·유지율은 방 전체 멤버의 확정 판정을 훑어야 나오는 값이라, 목록 조회마다
--  실시간으로 계산하면 정렬·페이징에서 비용이 급증한다(탐색 백엔드 테크스펙 §2·§4-4).
--  그래서 계산 결과만 여기 저장해 일반 정렬 컬럼처럼 쓴다.
--
--  이 테이블은 두 번째 원천이 아니라 **언제든 원천에서 다시 만들 수 있는 계산 결과**다.
--  그래서 capacity·category·participant_count 처럼 challenges 가 이미 소유한 값은 복제하지 않는다.
--
--  표본 규칙(정책 §4.4):
--    completion_rate = qualified_success_member_count / qualified_member_count
--                      단 qualified_member_count < 5 이면 NULL
--    retention_rate  = non_failed_member_count / participant_count
--                      단 total_progress_count < 30 또는 participant_count = 0 이면 NULL
--    UPCOMING 방은 둘 다 항상 NULL(아직 진행 지표가 없다)
--
--  인기 점수(recent_joins_24h·last_joined_at_24h)는 1시간 배치가 갱신한다.
-- =====================================================================

CREATE TABLE `challenge_stats` (
    `challenge_id`                   binary(16)     NOT NULL,
    `qualified_member_count`         int            NOT NULL DEFAULT 0
        COMMENT '확정 판정 10회 이상인 현재 멤버 수 — 완주율의 분모',
    `qualified_success_member_count` int            NOT NULL DEFAULT 0
        COMMENT '그중 성공률 80% 이상인 멤버 수 — 완주율의 분자',
    `completion_rate`                decimal(5, 4)  NULL
        COMMENT '완주율 0~1. 표본 미달·UPCOMING 이면 NULL(화면 미표시 + 해당 정렬에서 제외)',
    `total_progress_count`           int            NOT NULL DEFAULT 0
        COMMENT '현재 멤버들의 확정 판정 누적 합 — 유지율 표본 조건',
    `non_failed_member_count`        int            NOT NULL DEFAULT 0
        COMMENT '현재 멤버 중 확정 실패가 아닌 사람 수 — 유지율의 분자',
    `retention_rate`                 decimal(5, 4)  NULL
        COMMENT '유지율 0~1. 표본 미달·UPCOMING 이면 NULL',
    `recent_joins_24h`               int            NOT NULL DEFAULT 0
        COMMENT '최근 24시간 신규 참여 수 — 인기 정렬 기준값, 1시간 배치',
    `last_joined_at_24h`             datetime(6)    NULL
        COMMENT '인기 동점 처리용 마지막 참여 시각',
    `updated_at`                     datetime(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    `popularity_updated_at`          datetime(6)    NULL,
    PRIMARY KEY (`challenge_id`),
    KEY `ix_challenge_stats_completion` (`completion_rate` DESC, `challenge_id`),
    KEY `ix_challenge_stats_retention` (`retention_rate` DESC, `challenge_id`),
    KEY `ix_challenge_stats_popularity` (`recent_joins_24h` DESC, `last_joined_at_24h` DESC, `challenge_id`),
    CONSTRAINT `fk_challenge_stats_challenge` FOREIGN KEY (`challenge_id`) REFERENCES `challenges` (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 기존 방에도 기본 행을 만들어 둔다. 실제 지표는 첫 재계산에서 채워진다.
INSERT INTO `challenge_stats` (`challenge_id`) SELECT `id` FROM `challenges`;

-- 인기 배치가 최근 24시간 가입을 훑는 경로.
CREATE INDEX `ix_challenge_members_joined` ON `challenge_members` (`joined_at`, `challenge_id`);
