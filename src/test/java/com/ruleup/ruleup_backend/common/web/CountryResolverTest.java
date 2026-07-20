package com.ruleup.ruleup_backend.common.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/** 국가 코드 서버 해석(요청 기반) 로직 검증. Spring 컨텍스트 없이 MockHttpServletRequest로 빠르게. */
class CountryResolverTest {

    private final CountryResolver resolver = new CountryResolver();

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void bind(MockHttpServletRequest req) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    @Test
    void 지오헤더가_AcceptLanguage보다_우선() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("CloudFront-Viewer-Country", "kr");       // 소문자도 정규화
        req.addHeader("Accept-Language", "en-US");
        req.addPreferredLocale(Locale.US);
        bind(req);
        assertThat(resolver.resolve()).isEqualTo("KR");
    }

    @Test
    void 지오헤더_없으면_AcceptLanguage_로케일_사용() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Accept-Language", "ko-KR");
        req.addPreferredLocale(Locale.KOREA);
        bind(req);
        assertThat(resolver.resolve()).isEqualTo("KR");
    }

    @Test
    void AcceptLanguage_헤더_없으면_null_서버기본로케일로_오염_안함() {
        MockHttpServletRequest req = new MockHttpServletRequest();   // 헤더 없음
        bind(req);
        assertThat(resolver.resolve()).isNull();
    }

    @Test
    void 잘못된_지오헤더값은_무시() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("CloudFront-Viewer-Country", "ZZ1");   // 2자리 알파벳 아님
        bind(req);
        assertThat(resolver.resolve()).isNull();
    }

    @Test
    void 요청스레드_밖이면_클라이언트값만_사용() {
        assertThat(resolver.resolve()).isNull();
        assertThat(resolver.resolve("KR")).isEqualTo("KR");   // 배치 등 요청 밖에서도 클라 값은 인정
    }

    @Test
    void 지오헤더_없으면_클라이언트_기기지역_사용() {
        MockHttpServletRequest req = new MockHttpServletRequest();   // 지오 헤더/AcceptLanguage 없음
        bind(req);
        assertThat(resolver.resolve("kr")).isEqualTo("KR");         // 소문자 정규화
        assertThat(resolver.resolve("ko-KR")).isEqualTo("KR");     // 로케일 형태 → 지역 추출
    }

    @Test
    void 지오헤더가_클라이언트값보다_우선() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("CloudFront-Viewer-Country", "US");
        bind(req);
        assertThat(resolver.resolve("KR")).isEqualTo("US");   // 실제 접속 국가(CDN) 우선
    }

    @Test
    void 언어만_있는_AcceptLanguage는_지역없어_클라이언트값으로_폴백() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Accept-Language", "ko");           // 지역 없는 언어만
        req.addPreferredLocale(Locale.KOREAN);            // country == ""
        bind(req);
        assertThat(resolver.resolve()).isNull();          // 언어만으론 국가 확정 불가
        assertThat(resolver.resolve("KR")).isEqualTo("KR");
    }
}
