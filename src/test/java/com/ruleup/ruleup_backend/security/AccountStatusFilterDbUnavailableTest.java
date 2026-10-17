package com.ruleup.ruleup_backend.security;

import com.ruleup.ruleup_backend.sanction.SanctionService;
import com.ruleup.ruleup_backend.user.UserActivityService;
import com.ruleup.ruleup_backend.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.sql.SQLTransientConnectionException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 필터에서 DB 를 못 쓰면 /error 로 넘어가 401 이 되던 것 — 503 + Retry-After 여야 앱이 로그아웃하지 않는다. */
class AccountStatusFilterDbUnavailableTest {

    private final UserRepository users = mock(UserRepository.class);
    private final UserActivityService activity = mock(UserActivityService.class);
    private final AccountStatusFilter filter = new AccountStatusFilter(users, mock(SanctionService.class), activity);
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void login() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, AuthorityUtils.NO_AUTHORITIES));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void poolTimeoutIs503NotLoginRequired() throws Exception {
        doThrow(new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection",
                new SQLTransientConnectionException("ruleup-main - Connection is not available, request timed out after 5000ms")))
                .when(activity).touch(any());
        var res = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/users/me"), res, chain);

        assertThat(res.getStatus()).isEqualTo(503);
        assertThat(res.getHeader("Retry-After")).isEqualTo("2");
        assertThat(res.getContentAsString()).contains("TEMPORARILY_UNAVAILABLE");
        assertThat(chain.getRequest()).isNull();   // 컨트롤러까지 가지 않는다
    }

    @Test
    void otherFailuresStillPropagate() {
        when(users.findStatusById(any())).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/users/me"),
                new MockHttpServletResponse(), new MockFilterChain()))
                .hasMessageContaining("boom");
    }
}
