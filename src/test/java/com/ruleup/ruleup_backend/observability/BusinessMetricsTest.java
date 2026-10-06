package com.ruleup.ruleup_backend.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final BusinessMetrics metrics = new BusinessMetrics(registry);

    private double count(String name, String tag, String value) {
        return registry.get(name).tag(tag, value).counter().count();
    }

    @Test
    @DisplayName("한 번도 안 일어난 결과도 0 으로 미리 올라가 있다 — 「없음」이면 경보가 데이터 부족에 머문다")
    void allTagValuesRegisteredUpFront() {
        assertThat(registry.get("biz.signup").counters()).hasSize(2);
        assertThat(registry.get("biz.login").counters()).hasSize(3);
        assertThat(registry.get("biz.verification.attempt").counters()).hasSize(2);
        assertThat(registry.get("biz.challenge.draft").counters()).hasSize(5);    // ai×3 + template + clone
        assertThat(registry.get("biz.challenge.created").counters()).hasSize(6);  // 경로 3 × 수정 여부 2
        assertThat(registry.get("biz.challenge.joined").counters()).hasSize(2);
        assertThat(registry.getMeters()).allSatisfy(m ->
                assertThat(m.getId().getName()).startsWith("biz."));
    }

    @Test
    @DisplayName("가입·로그인·인증 시도가 각자의 태그 값으로 따로 세어진다")
    void countsByTag() {
        metrics.signupSucceeded();
        metrics.signupFailed();
        metrics.signupFailed();
        metrics.loginExisting();
        metrics.loginNewUser();
        metrics.loginFailed();
        metrics.loginFailed();
        metrics.loginFailed();
        metrics.syncAttempt();
        metrics.syncAttempt();
        metrics.manualAttempt();

        assertThat(count("biz.signup", "result", "success")).isEqualTo(1);
        assertThat(count("biz.signup", "result", "failure")).isEqualTo(2);
        assertThat(count("biz.login", "outcome", "existing")).isEqualTo(1);
        assertThat(count("biz.login", "outcome", "new_user")).isEqualTo(1);
        assertThat(count("biz.login", "outcome", "failure")).isEqualTo(3);
        assertThat(count("biz.verification.attempt", "method", "sync")).isEqualTo(2);
        assertThat(count("biz.verification.attempt", "method", "manual")).isEqualTo(1);
    }

    @Test
    @DisplayName("챌린지 참여 — 초안 경로·결과, 생성 경로·초안 수정 여부, 가입 경로가 따로 세어진다")
    void participationCountsByTag() {
        metrics.draftCreated(com.ruleup.ruleup_backend.challenge.draft.ChallengeDraft.Origin.AI);
        metrics.aiDraftBlocked();
        metrics.aiDraftFailed();
        metrics.aiDraftFailed();
        metrics.draftCreated(com.ruleup.ruleup_backend.challenge.draft.ChallengeDraft.Origin.TEMPLATE);
        metrics.draftCreated(com.ruleup.ruleup_backend.challenge.draft.ChallengeDraft.Origin.CLONE);
        metrics.challengeCreated(com.ruleup.ruleup_backend.challenge.draft.ChallengeDraft.Origin.AI, true);
        metrics.challengeCreated(com.ruleup.ruleup_backend.challenge.draft.ChallengeDraft.Origin.TEMPLATE, false);
        metrics.challengeCreated(com.ruleup.ruleup_backend.challenge.draft.ChallengeDraft.Origin.TEMPLATE, false);
        metrics.joined(false);
        metrics.joined(true);
        metrics.joined(true);

        assertThat(registry.get("biz.challenge.draft").tags("origin", "ai", "result", "success").counter().count()).isEqualTo(1);
        assertThat(registry.get("biz.challenge.draft").tags("origin", "ai", "result", "blocked").counter().count()).isEqualTo(1);
        assertThat(registry.get("biz.challenge.draft").tags("origin", "ai", "result", "failure").counter().count()).isEqualTo(2);
        assertThat(registry.get("biz.challenge.draft").tags("origin", "template", "result", "success").counter().count()).isEqualTo(1);
        assertThat(registry.get("biz.challenge.draft").tags("origin", "clone", "result", "success").counter().count()).isEqualTo(1);
        assertThat(registry.get("biz.challenge.created").tags("origin", "ai", "edited", "yes").counter().count()).isEqualTo(1);
        assertThat(registry.get("biz.challenge.created").tags("origin", "template", "edited", "no").counter().count()).isEqualTo(2);
        assertThat(count("biz.challenge.joined", "source", "explore")).isEqualTo(1);
        assertThat(count("biz.challenge.joined", "source", "invite")).isEqualTo(2);
    }
}
