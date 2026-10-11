package com.ruleup.ruleup_backend.observability.sli;

import com.ruleup.ruleup_backend.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class SliClassificationTest {

    @ParameterizedTest(name = "{0} {1} → {2}")
    @CsvSource({
            "POST, /api/v1/auth/oauth/google, oauth_google",
            "POST, /api/v1/auth/oauth/kakao, oauth_kakao",
            "POST, /api/v1/auth/refresh, refresh",
            "GET, /api/v1/challenges/explore, explore",
            "GET, /api/v1/challenges/0b7a6c1e-1111-4222-8333-944455556666, detail",
            "POST, /api/v1/challenges/0b7a6c1e-1111-4222-8333-944455556666/members, join",
            "POST, /api/v1/verifications/sync, sync",
            "POST, /api/v1/challenges/0b7a6c1e-1111-4222-8333-944455556666/verifications, manual",
            "GET, /api/v1/challenges/0b7a6c1e-1111-4222-8333-944455556666/verifications/today, today",
            "GET, /api/v1/verifications/progress, progress"
    })
    @DisplayName("노션 §1 표의 메서드·경로가 고정 Operation 이름으로 묶인다")
    void matchesCatalogRoutes(String method, String path, String expected) {
        assertThat(SliOperation.match(method, path)).extracting(SliOperation::id).isEqualTo(expected);
    }

    @Test
    @DisplayName("탐색은 상세({challengeId})보다 먼저 잡히고, 메서드가 다르면 CUJ 가 아니다")
    void literalBeforeVariableAndMethodMatters() {
        assertThat(SliOperation.match("GET", "/api/v1/challenges/explore")).isEqualTo(SliOperation.EXPLORE);
        assertThat(SliOperation.match("PATCH", "/api/v1/challenges/abc")).isNull();
        assertThat(SliOperation.match("GET", "/api/v1/challenges/abc/members")).isNull();
        assertThat(SliOperation.match("GET", "/health/public")).isNull();
    }

    @Test
    @DisplayName("5xx·이름 없는 4xx·계약 회귀 400 은 실패, 이름 붙은 정책 거절은 적격에서 뺀다")
    void classifiesOutcomes() {
        assertThat(SliOutcome.classify(200, null, false)).isEqualTo(SliOutcome.GOOD);
        assertThat(SliOutcome.classify(503, ErrorCode.EXPLORE_TEMPORARILY_UNAVAILABLE, false)).isEqualTo(SliOutcome.BAD);
        assertThat(SliOutcome.classify(500, null, false)).isEqualTo(SliOutcome.BAD);
        assertThat(SliOutcome.classify(429, null, false)).as("이름 없는 429 = 과부하").isEqualTo(SliOutcome.BAD);
        assertThat(SliOutcome.classify(429, ErrorCode.SYNC_TOO_FREQUENT, false)).isEqualTo(SliOutcome.EXCLUDED);
        assertThat(SliOutcome.classify(400, ErrorCode.INVALID_REQUEST, false)).as("계약 회귀").isEqualTo(SliOutcome.BAD);
        assertThat(SliOutcome.classify(401, ErrorCode.SESSION_EXPIRED, false)).isEqualTo(SliOutcome.EXCLUDED);
        assertThat(SliOutcome.classify(405, null, false)).as("미분류 4xx").isEqualTo(SliOutcome.BAD);
        assertThat(SliOutcome.classify(500, null, true)).as("합성 점검").isEqualTo(SliOutcome.EXCLUDED);
    }
}
