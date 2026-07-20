package com.ruleup.ruleup_backend.challenge;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.dto.ChallengeRecommendationResponse;
import com.ruleup.ruleup_backend.challenge.recommendation.ChallengeDraftClient;
import com.ruleup.ruleup_backend.challenge.recommendation.ChallengeDraftSuggestion;
import com.ruleup.ruleup_backend.challenge.service.ChallengeRecommendationService;
import com.ruleup.ruleup_backend.routine.dto.RoutineRecommendationRequest;
import com.ruleup.ruleup_backend.routine.match.RoutineCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 추천 Step1·2 차단 → fallback:true, 정상 → maxMannerTemperature/maxParticipants 포함.
 * ChallengeDraftClient 를 제목 기반 스텁으로 대체(제목에 "차단" 포함 시 block()).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ChallengeRecommendationFallbackIT {

    @TestConfiguration
    static class StubConfig {
        @Bean @Primary
        ChallengeDraftClient stubDraftClient() {
            return (String title, String description, List<RoutineCandidate> candidates) ->
                    (title != null && title.contains("차단"))
                            ? ChallengeDraftSuggestion.block()
                            : ChallengeDraftSuggestion.empty();   // 매칭 없이 기본 템플릿 초안
        }
    }

    @Autowired ChallengeRecommendationService service;

    @Test
    @DisplayName("Step1·2 차단 입력이면 fallback:true")
    void blockedInputReturnsFallback() {
        ChallengeRecommendationResponse res = service.recommend(
                UUID.randomUUID(), new RoutineRecommendationRequest("차단 대상 제목", null));
        assertThat(res.fallback()).isTrue();
        assertThat(res.recommendedMethod()).isNull();
    }

    @Test
    @DisplayName("정상 입력이면 fallback:false + maxMannerTemperature/maxParticipants 포함")
    void normalInputIncludesCaps() {
        ChallengeRecommendationResponse res = service.recommend(
                UUID.randomUUID(), new RoutineRecommendationRequest("아침 달리기", null));
        assertThat(res.fallback()).isFalse();
        assertThat(res.maxMannerTemperature()).isEqualByComparingTo(new BigDecimal("36.5")); // 신규 유저 초기 온도
        assertThat(res.maxParticipants()).isEqualTo(10);
    }
}
