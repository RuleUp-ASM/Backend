package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.verification.domain.SignalExclusion;
import com.ruleup.ruleup_backend.verification.domain.VerificationMethod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 평가 근거 → 배제 로그 매핑 (인증 공통 5-3 · 3절 ①).
 *
 * <p>어떤 evidence 키를 어떤 사유로 옮기는지가 신호 위생 층의 계약이다. 이 매핑이 틀리면
 * 이상탐지가 <b>엉뚱한 것을 센다</b> — 직접 입력을 「조작」으로 세면 사람을 잘못 몰고,
 * 조작을 놓치면 탐지가 성립하지 않는다. 스프링을 띄우지 않는다(순수 함수).
 */
class SignalHygieneMappingTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID DAILY = UUID.randomUUID();
    private static final Instant AT = Instant.parse("2026-09-14T00:00:00Z");

    private List<SignalExclusion> rows(VerificationMethod method, Map<String, Object> evidence) {
        return SignalExclusionRecorder.hygieneRows(USER, DAILY, method, evidence, AT);
    }

    @Test
    @DisplayName("조작 위치와 저정확도는 다른 사유로 갈린다 — 하나는 의심이고 하나는 기기 한계다")
    void locationHygieneSplitsByReason() {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("excludedMock", 2);
        evidence.put("excludedAccuracy", 5);
        evidence.put("dwellMinutes", 12);   // 판정 값은 배제가 아니다

        assertThat(rows(VerificationMethod.GPS_PRESENCE, evidence))
                .extracting(SignalExclusion::getReason, SignalExclusion::getSignalCount,
                        SignalExclusion::getSignalType)
                .containsExactly(
                        tuple("MOCK", 2, "LOCATION"),
                        tuple("ACCURACY_LOW", 5, "LOCATION"));
    }

    @Test
    @DisplayName("직접 입력과 비신뢰 출처를 가른다 — 유저가 할 일이 다르다")
    void healthOriginsSplitManualFromUntrusted() {
        Map<String, Object> evidence = Map.of(
                "rejectedOrigins", List.of("user:MANUAL", "unknown:UNTRUSTED_ORIGIN", "x:NO_ORIGIN"));

        assertThat(rows(VerificationMethod.HEALTH, evidence))
                .extracting(SignalExclusion::getReason, SignalExclusion::getSignalCount)
                .containsExactlyInAnyOrder(
                        tuple("MANUAL_ENTRY", 1),
                        tuple("UNTRUSTED_SOURCE", 2));   // 출처 없음도 신뢰할 수 없는 기록이다
    }

    @Test
    @DisplayName("수면의 비신뢰 플래그가 같은 사유를 두 번 세지 않는다")
    void sleepFlagDoesNotDoubleCount() {
        assertThat(rows(VerificationMethod.SLEEP, Map.of("untrustedExcluded", true)))
                .extracting(SignalExclusion::getReason, SignalExclusion::getSignalType)
                .containsExactly(tuple("UNTRUSTED_SOURCE", "SLEEP"));

        assertThat(rows(VerificationMethod.HEALTH, Map.of(
                "untrustedExcluded", true,
                "rejectedOrigins", List.of("unknown:UNTRUSTED_ORIGIN"))))
                .as("출처 목록이 이미 세고 있으면 플래그로 한 줄 더 만들지 않는다")
                .hasSize(1);
    }

    @Test
    @DisplayName("뺀 것이 없으면 행도 없다 — 정상 판정에 빈 기록을 쌓지 않는다")
    void cleanEvaluationRecordsNothing() {
        assertThat(rows(VerificationMethod.GPS_PRESENCE, Map.of("dwellMinutes", 45))).isEmpty();
        assertThat(rows(VerificationMethod.GPS_PRESENCE, Map.of())).isEmpty();
        assertThat(rows(VerificationMethod.GPS_PRESENCE, null)).isEmpty();
    }

    @Test
    @DisplayName("0 건은 기록하지 않는다 — 평가기가 0 을 남겨도 배제는 없었던 것이다")
    void zeroCountIsNotAnExclusion() {
        assertThat(rows(VerificationMethod.GPS_PRESENCE, Map.of("excludedMock", 0))).isEmpty();
    }
}
