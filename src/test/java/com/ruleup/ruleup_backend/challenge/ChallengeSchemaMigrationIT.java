package com.ruleup.ruleup_backend.challenge;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V3 마이그레이션 스키마 계약 테스트 — 챌린지 생성·라이프사이클 스펙(백엔드 테크 스펙 4-1).
 *
 * 검증 대상:
 *  1) 챌린지 도메인 테이블 snake_case 전면 전환(Challenge→challenges 등) — 구 camelCase 테이블 부재.
 *  2) challenges 신규 컬럼 — ai_title(대체 표시), version(수정 충돌 감지), min_tier(표시 티어 게이트),
 *     visibility/ranking_visible(공개 범위), param_specs(목표값 스펙), penalties(서버 강제 패널티),
 *     항목별 모더레이션 상태(title/description/image) + 반복 거부 잠금(1시간 3회 → 1시간 잠금).
 *  3) 신규 테이블 — challenge_drafts(초안 24시간 보관·원본 대조), idempotency_keys(생성 멱등, DB 유니크),
 *     이력 3종(challenge_history·challenge_member_history·challenge_final_ranking — 하드 삭제와 완료 기록
 *     조회의 모순 해소), challenge_image_uploads(이미지 소유 검증·고아 24시간 정리).
 *  4) 기존 컬럼 중 스펙에서 유지가 필요한 것(repeat_days — 인증 판정 사용)은 snake_case 로 존속.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ChallengeSchemaMigrationIT {

    @Autowired
    JdbcTemplate jdbc;

    private Set<String> columnsOf(String table) {
        List<String> cols = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns " +
                        "WHERE table_schema = DATABASE() AND table_name = ?", String.class, table);
        return Set.copyOf(cols);
    }

    private boolean tableExists(String table) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables " +
                        "WHERE table_schema = DATABASE() AND table_name = ?", Integer.class, table);
        return n != null && n > 0;
    }

    @Nested
    @DisplayName("테이블명 snake_case 전환")
    class Rename {

        @Test
        @DisplayName("challenges·challenge_members·challenge_delegations 로 전환되고 구 camelCase 테이블은 없다")
        void renamedTables() {
            assertThat(tableExists("challenges")).isTrue();
            assertThat(tableExists("challenge_members")).isTrue();
            assertThat(tableExists("challenge_delegations")).isTrue();
            assertThat(tableExists("Challenge")).isFalse();
            assertThat(tableExists("ChallengeMember")).isFalse();
            assertThat(tableExists("ChallengeDelegation")).isFalse();
        }

        @Test
        @DisplayName("challenges 컬럼이 snake_case 다 (owner_id·image_url·capacity·start_date 등)")
        void challengesColumnsSnakeCase() {
            Set<String> cols = columnsOf("challenges");
            assertThat(cols).contains(
                    "id", "owner_id", "title", "description", "image_url", "category",
                    "mode", "capacity", "repeat_days", "start_date", "end_date",
                    "template_id", "verification_config", "params", "status",
                    "participant_count", "created_at", "updated_at");
            // camelCase 잔재 없음
            assertThat(cols).doesNotContain(
                    "creatorId", "imageUrl", "participationType", "maxParticipants",
                    "startDate", "endDate", "templateId", "verificationConfig",
                    "participantCount", "createdAt", "updatedAt", "moderationStatus");
        }

        @Test
        @DisplayName("challenge_members 컬럼이 snake_case 다")
        void memberColumnsSnakeCase() {
            Set<String> cols = columnsOf("challenge_members");
            assertThat(cols).contains("id", "challenge_id", "user_id", "role", "status", "joined_at");
            assertThat(cols).doesNotContain("challengeId", "userId", "joinedAt");
        }
    }

    @Nested
    @DisplayName("challenges 신규 컬럼 — 생성·수정·심사 스펙")
    class NewColumns {

        @Test
        @DisplayName("ai_title(대체 표시)·version(충돌 감지)·min_tier·visibility·ranking_visible 이 있다")
        void specColumns() {
            Set<String> cols = columnsOf("challenges");
            assertThat(cols).contains("ai_title", "version", "min_tier", "visibility", "ranking_visible");
        }

        @Test
        @DisplayName("param_specs(목표값 스펙)·penalties(서버 강제 패널티) JSON 컬럼이 있다")
        void paramAndPenaltyColumns() {
            assertThat(columnsOf("challenges")).contains("param_specs", "penalties");
        }

        @Test
        @DisplayName("항목별 모더레이션 상태 + 반복 거부 잠금 컬럼이 있다")
        void moderationColumns() {
            Set<String> cols = columnsOf("challenges");
            assertThat(cols).contains(
                    "moderation_title", "moderation_description", "moderation_image",
                    "moderation_locked_until", "moderation_reject_count", "moderation_reject_window_start");
        }

        @Test
        @DisplayName("version 기본값 0 — 기존 행도 충돌 감지 가능")
        void versionDefaultZero() {
            String def = jdbc.queryForObject(
                    "SELECT column_default FROM information_schema.columns " +
                            "WHERE table_schema = DATABASE() AND table_name = 'challenges' AND column_name = 'version'",
                    String.class);
            assertThat(def).isEqualTo("0");
        }
    }

    @Nested
    @DisplayName("challenge_drafts — 초안 원본 24시간 보관")
    class Drafts {

        @Test
        @DisplayName("draft_id PK·user_id·origin(AI/TEMPLATE/CLONE)·payload·title·description·expires_at 컬럼")
        void draftColumns() {
            Set<String> cols = columnsOf("challenge_drafts");
            assertThat(cols).contains(
                    "id", "user_id", "origin", "source_challenge_id", "template_id",
                    "title", "description", "payload", "created_at", "expires_at");
        }

        @Test
        @DisplayName("origin enum 은 AI/TEMPLATE/CLONE 세 값이다")
        void originEnum() {
            String type = jdbc.queryForObject(
                    "SELECT column_type FROM information_schema.columns " +
                            "WHERE table_schema = DATABASE() AND table_name = 'challenge_drafts' AND column_name = 'origin'",
                    String.class);
            assertThat(type).contains("AI").contains("TEMPLATE").contains("CLONE");
        }
    }

    @Nested
    @DisplayName("idempotency_keys — 생성 멱등(DB 유니크)")
    class Idempotency {

        @Test
        @DisplayName("user_id·idempotency_key·request_hash·response_snapshot 컬럼 + (user_id, key) 유니크")
        void idempotencyTable() {
            Set<String> cols = columnsOf("idempotency_keys");
            assertThat(cols).contains("user_id", "idempotency_key", "request_hash", "response_snapshot", "created_at");

            Integer uniq = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.statistics " +
                            "WHERE table_schema = DATABASE() AND table_name = 'idempotency_keys' " +
                            "AND non_unique = 0 AND column_name IN ('user_id','idempotency_key')",
                    Integer.class);
            assertThat(uniq).isGreaterThanOrEqualTo(2);   // 복합 유니크 인덱스의 두 컬럼
        }
    }

    @Nested
    @DisplayName("이력 테이블 3종 — 하드 삭제 후 완료 기록·최종 랭킹 열람")
    class History {

        @Test
        @DisplayName("challenge_history — 삭제 직전 스냅샷")
        void challengeHistory() {
            assertThat(columnsOf("challenge_history")).contains(
                    "challenge_id", "title_snapshot", "image_snapshot", "category",
                    "start_date", "end_date", "deleted_at");
        }

        @Test
        @DisplayName("challenge_member_history — 멤버 최종 상태")
        void memberHistory() {
            assertThat(columnsOf("challenge_member_history")).contains(
                    "challenge_id", "user_id", "final_role", "left_type", "left_at", "final_success_rate");
        }

        @Test
        @DisplayName("challenge_final_ranking — 최종 랭킹 스냅샷")
        void finalRanking() {
            assertThat(columnsOf("challenge_final_ranking")).contains(
                    "challenge_id", "user_id", "rank_no", "score_snapshot");
        }
    }

    @Nested
    @DisplayName("challenge_image_uploads — 업로드 소유 검증·고아 정리")
    class ImageUploads {

        @Test
        @DisplayName("user_id·image_url(유니크)·registered_at·created_at 컬럼")
        void imageUploadColumns() {
            Set<String> cols = columnsOf("challenge_image_uploads");
            assertThat(cols).contains("id", "user_id", "image_url", "registered_at", "created_at");

            Integer uniq = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.statistics " +
                            "WHERE table_schema = DATABASE() AND table_name = 'challenge_image_uploads' " +
                            "AND non_unique = 0 AND column_name = 'image_url'",
                    Integer.class);
            assertThat(uniq).isGreaterThanOrEqualTo(1);
        }
    }
}
