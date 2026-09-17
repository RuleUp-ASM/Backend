package com.ruleup.ruleup_backend.verification;

import com.ruleup.ruleup_backend.verification.domain.FailureEvidence;
import com.ruleup.ruleup_backend.verification.domain.GapReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실패 판정의 <b>설명 근거</b> — 개인정보보호법의 자동화된 결정 조항 대응(공통 5-8).
 *
 * <p>자동 판정이 실패 → 감점 → 강퇴로 이어지므로 「왜 실패했는지」를 숫자로 설명할 수 있어야
 * 하고, 그 설명은 <b>판정 당시 기준값</b>으로 고정돼야 한다. 스프링을 띄우지 않는다 — 확정
 * 배치가 부르는 경로라 순수 함수인 것이 계약이다.
 */
class VerificationFailureExplanationTest {

    @Nested
    @DisplayName("판정 근거 요약")
    class Summary {

        @Test
        @DisplayName("숫자가 있으면 목표와 실제를 나란히 적는다 — 화면에 그대로 띄울 문장이다")
        void showsActualAgainstGoal() {
            FailureEvidence evidence = FailureEvidence.of("INSUFFICIENT_DWELL",
                    Map.of("dwellMinutes", 42, "goalMinutes", 60));

            assertThat(evidence.summary()).isEqualTo("체류 42분 / 목표 60분");
            assertThat(evidence.expected()).containsExactly(Map.entry("goalMinutes", 60));
            assertThat(evidence.actual()).containsExactly(Map.entry("dwellMinutes", 42));
        }

        @Test
        @DisplayName("최대 사용형은 목표가 아니라 상한으로 적는다 — 넘겨서 실패한 것이다")
        void limitTypeReadsAsCeiling() {
            assertThat(FailureEvidence.of("USAGE_EXCEEDED",
                    Map.of("usageMinutes", 95, "goalMinutes", 60)).summary())
                    .isEqualTo("사용 95분 / 상한 60분");
        }

        @Test
        @DisplayName("잴 수 없었던 경우는 무엇을 고쳐야 하는지로 설명한다")
        void gapsExplainWhatToFix() {
            assertThat(FailureEvidence.of("PERMISSION_MISSING", Map.of()).summary())
                    .contains("권한");
            assertThat(FailureEvidence.of("NO_SIGNAL_RECEIVED", Map.of()).summary())
                    .contains("신호");
        }

        @Test
        @DisplayName("모르는 사유도 문장이 나온다 — 컬럼이 NOT NULL 이고 빈 설명은 설명이 아니다")
        void unknownReasonStillExplains() {
            FailureEvidence evidence = FailureEvidence.of("SOMETHING_NEW", Map.of());

            assertThat(evidence.summary()).isNotBlank();
        }

        @Test
        @DisplayName("사유 코드는 문장에 섞이지 않는다 — 이 값은 화면에 그대로 나간다")
        void summaryNeverLeaksTheReasonCode() {
            assertThat(FailureEvidence.of("SOMETHING_NEW", Map.of()).summary())
                    .doesNotContain("SOMETHING_NEW");
            assertThat(FailureEvidence.of("GEOFENCE_NOT_CONFIGURED", Map.of()).summary())
                    .doesNotContain("GEOFENCE_NOT_CONFIGURED")
                    .contains("장소");
        }

        @Test
        @DisplayName("기준값이 없으면 숫자를 지어내지 않고 일반 문장으로 내려간다")
        void missingGoalFallsBack() {
            assertThat(FailureEvidence.of("INSUFFICIENT_DWELL", Map.of("dwellMinutes", 42)).summary())
                    .doesNotContain("목표");
        }
    }

    @Nested
    @DisplayName("저장 값")
    class Stored {

        @Test
        @DisplayName("내부 이월 상태는 설명에 담지 않는다 — 멱등 키·열린 구간은 근거가 아니다")
        void carriesOnlyExplanatoryKeys() {
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("dwellMinutes", 42);
            raw.put("goalMinutes", 60);
            raw.put("seenTransitions", List.of("t1", "t2"));
            raw.put("lastInsideAt", "2026-09-13T10:00:00Z");

            FailureEvidence evidence = FailureEvidence.of("INSUFFICIENT_DWELL", raw);

            assertThat(evidence.expected()).containsOnlyKeys("goalMinutes");
            assertThat(evidence.actual()).containsOnlyKeys("dwellMinutes");
        }

        @Test
        @DisplayName("evidence 가 아예 없으면 기준값·실제값은 null 이다")
        void emptyEvidenceStoresNothing() {
            FailureEvidence evidence = FailureEvidence.of("NO_SIGNAL_RECEIVED", null);

            assertThat(evidence.expected()).isNull();
            assertThat(evidence.actual()).isNull();
            assertThat(evidence.summary()).isNotBlank();
        }
    }

    @Nested
    @DisplayName("판정 불가 사유")
    class Gap {

        @Test
        @DisplayName("권한 부족과 신호 없음만 데이터 부족이다 — 목표 미달은 잴 수 있었던 실패다")
        void onlyMeasurementGapsCount() {
            assertThat(GapReason.of("PERMISSION_MISSING")).isEqualTo("PERMISSION_MISSING");
            assertThat(GapReason.of("NO_SIGNAL_RECEIVED")).isEqualTo("NO_SIGNAL");
            assertThat(GapReason.of("UNTRUSTED_HEALTH_SOURCE")).isEqualTo("NO_SIGNAL");

            assertThat(GapReason.of("INSUFFICIENT_STEPS")).isNull();
            assertThat(GapReason.of("WOKE_UP_LATE")).isNull();
            assertThat(GapReason.of(null)).isNull();
        }
    }
}
