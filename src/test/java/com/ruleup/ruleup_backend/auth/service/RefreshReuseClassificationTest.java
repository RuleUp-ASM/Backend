package com.ruleup.ruleup_backend.auth.service;

import com.ruleup.ruleup_backend.observability.ops.SecuritySlackAlert.RefreshReuse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshReuseClassificationTest {

    @Test
    @DisplayName("후속 토큰이 다시 회전됐으면 탈취 의심 — 나머지 상태와 무관하다")
    void successorRotated() {
        assertThat(AuthService.classifyReuse(List.of(true), true)).isEqualTo(RefreshReuse.SUCCESSOR_ROTATED);
        assertThat(AuthService.classifyReuse(List.of(true, false), true)).isEqualTo(RefreshReuse.SUCCESSOR_ROTATED);
    }

    @Test
    @DisplayName("후속이 없거나 모두 폐기됐으면 이미 끝난 세션 — 로그아웃·다른 기기 로그인·정지")
    void sessionEnded() {
        assertThat(AuthService.classifyReuse(List.of(), false)).isEqualTo(RefreshReuse.SESSION_ENDED);
        assertThat(AuthService.classifyReuse(List.of(true, true), false)).isEqualTo(RefreshReuse.SESSION_ENDED);
    }

    @Test
    @DisplayName("후속이 살아 있는데 유예하지 못했으면 창(또는 토큰 수명)을 넘긴 것")
    void graceExpired() {
        assertThat(AuthService.classifyReuse(List.of(false), false)).isEqualTo(RefreshReuse.GRACE_EXPIRED);
        assertThat(AuthService.classifyReuse(List.of(true, false), false)).isEqualTo(RefreshReuse.GRACE_EXPIRED);
    }
}
