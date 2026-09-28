package com.ruleup.ruleup_backend.common.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    @DisplayName("요청 중에는 MDC 에 requestId 가 있고, 응답 헤더로 돌려주며, 끝나면 지운다")
    void putsRequestIdForTheRequestOnly() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response,
                new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
                    @Override
                    protected void service(jakarta.servlet.http.HttpServletRequest req,
                                           jakarta.servlet.http.HttpServletResponse res) {
                        seen.set(MDC.get(RequestIdFilter.MDC_KEY));
                    }
                }));

        assertThat(seen.get()).isNotBlank();
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo(seen.get());
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).as("스레드 풀에 남으면 다음 요청 로그에 섞인다").isNull();
    }

    @Test
    @DisplayName("앱이 보낸 안전한 ID 는 그대로 쓰고, 로그를 오염시킬 수 있는 값은 버린다")
    void acceptsOnlySafeIncomingIds() throws Exception {
        assertThat(roundTrip("app-7f3c9a21")).isEqualTo("app-7f3c9a21");
        assertThat(roundTrip("bad\nINFO forged line")).isNotEqualTo("bad\nINFO forged line");
    }

    private String roundTrip(String incoming) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, incoming);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getHeader(RequestIdFilter.HEADER);
    }
}
