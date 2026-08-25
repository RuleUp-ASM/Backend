package com.ruleup.ruleup_backend.common.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
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
    @DisplayName("지오 헤더가 Accept-Language 보다 우선한다")
    void geoHeaderWinsOverAcceptLanguage() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("CloudFront-Viewer-Country", "kr");       // 소문자도 정규화
        req.addHeader("Accept-Language", "en-US");
        req.addPreferredLocale(Locale.US);
        bind(req);
        assertThat(resolver.resolve()).isEqualTo("KR");
    }

    @Test
    @DisplayName("지오 헤더가 없으면 Accept-Language 로케일을 쓴다")
    void fallsBackToAcceptLanguageLocale() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Accept-Language", "ko-KR");
        req.addPreferredLocale(Locale.KOREA);
        bind(req);
        assertThat(resolver.resolve()).isEqualTo("KR");
    }

    @Test
    @DisplayName("Accept-Language 헤더가 없으면 null — 서버 기본 로케일로 오염되지 않는다")
    void returnsNullWithoutAcceptLanguageHeader() {
        MockHttpServletRequest req = new MockHttpServletRequest();   // 헤더 없음
        bind(req);
        assertThat(resolver.resolve()).isNull();
    }

    @Test
    @DisplayName("형식이 잘못된 지오 헤더 값은 무시한다")
    void ignoresMalformedGeoHeader() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("CloudFront-Viewer-Country", "ZZ1");   // 2자리 알파벳 아님
        bind(req);
        assertThat(resolver.resolve()).isNull();
    }

    @Test
    @DisplayName("요청 스레드 밖(배치 등)에서는 클라이언트 값만 쓴다")
    void usesOnlyClientValueOutsideRequestThread() {
        assertThat(resolver.resolve()).isNull();
        assertThat(resolver.resolve("KR")).isEqualTo("KR");   // 배치 등 요청 밖에서도 클라 값은 인정
    }

    @Test
    @DisplayName("지오 헤더가 없으면 클라이언트가 보낸 기기 지역을 쓴다")
    void usesClientDeviceRegionWithoutGeoHeader() {
        MockHttpServletRequest req = new MockHttpServletRequest();   // 지오 헤더/AcceptLanguage 없음
        bind(req);
        assertThat(resolver.resolve("kr")).isEqualTo("KR");         // 소문자 정규화
        assertThat(resolver.resolve("ko-KR")).isEqualTo("KR");     // 로케일 형태 → 지역 추출
    }

    @Test
    @DisplayName("지오 헤더가 클라이언트 값보다 우선한다")
    void geoHeaderWinsOverClientValue() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("CloudFront-Viewer-Country", "US");
        bind(req);
        assertThat(resolver.resolve("KR")).isEqualTo("US");   // 실제 접속 국가(CDN) 우선
    }

    @Test
    @DisplayName("언어만 있는 Accept-Language 는 지역이 없어 클라이언트 값으로 폴백한다")
    void fallsBackToClientValueWhenLanguageOnly() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Accept-Language", "ko");           // 지역 없는 언어만
        req.addPreferredLocale(Locale.KOREAN);            // country == ""
        bind(req);
        assertThat(resolver.resolve()).isNull();          // 언어만으론 국가 확정 불가
        assertThat(resolver.resolve("KR")).isEqualTo("KR");
    }

    // ===== 2026-08-25: country_code 가 NULL 로 남던 원인 재현 + 타임존 폴백 =====

    @Test
    @DisplayName("[회귀] 지오 헤더·기기 지역·Accept-Language 가 모두 없으면 해석에 실패한다 — 실제 안드 계약이 이 상태였다")
    void realAndroidContractResolvesToNothing() {
        MockHttpServletRequest req = new MockHttpServletRequest();   // ALB 배포 = 지오 헤더 없음
        bind(req);
        // deviceInfo.country 미전송(선택 필드) + OkHttp 는 Accept-Language 를 기본으로 붙이지 않는다.
        assertThat(resolver.resolve(null, null)).isNull();
    }

    @Test
    @DisplayName("CDN 별 지오 헤더 이름을 모두 인정한다 (CloudFront·Cloudflare·Fastly·Vercel·일반 프록시)")
    void acceptsAllKnownGeoHeaderNames() {
        for (String header : new String[]{
                "CloudFront-Viewer-Country", "CF-IPCountry", "Fastly-Client-Country",
                "X-Vercel-IP-Country", "X-Country-Code", "X-Geo-Country", "X-AppEngine-Country"}) {
            MockHttpServletRequest req = new MockHttpServletRequest();
            req.addHeader(header, "kr");
            bind(req);
            assertThat(resolver.resolve()).as(header).isEqualTo("KR");
            clear();
        }
    }

    @Test
    @DisplayName("기기 지역이 없어도 IANA 타임존으로 국가를 해석한다 — 안드 기기는 타임존을 항상 들고 있다")
    void resolvesFromIanaTimeZone() {
        assertThat(resolver.resolve(null, "Asia/Seoul")).isEqualTo("KR");
        assertThat(resolver.resolve(null, "Asia/Tokyo")).isEqualTo("JP");
        assertThat(resolver.resolve(null, "America/Los_Angeles")).isEqualTo("US");
    }

    @Test
    @DisplayName("여러 나라가 함께 쓰는 타임존과 국가가 없는 타임존은 해석하지 않는다 — 틀린 값보다 없는 값이 낫다")
    void ambiguousOrCountrylessTimeZoneResolvesToNull() {
        assertThat(resolver.resolve(null, "UTC")).isNull();
        assertThat(resolver.resolve(null, "Etc/GMT+9")).isNull();
        assertThat(resolver.resolve(null, "not/a-zone")).isNull();
        assertThat(resolver.resolve(null, "")).isNull();
    }

    @Test
    @DisplayName("우선순위: 지오 헤더 > 기기 지역 > Accept-Language > 타임존")
    void timeZoneIsTheLastResort() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Accept-Language", "en-US");
        req.addPreferredLocale(Locale.US);
        bind(req);
        // Accept-Language(US)가 있으면 타임존(Asia/Seoul)보다 앞선다.
        assertThat(resolver.resolve(null, "Asia/Seoul")).isEqualTo("US");
        // 기기 지역은 Accept-Language 보다 앞선다.
        assertThat(resolver.resolve("JP", "Asia/Seoul")).isEqualTo("JP");

        clear();
        MockHttpServletRequest geo = new MockHttpServletRequest();
        geo.addHeader("CF-IPCountry", "DE");
        bind(geo);
        assertThat(resolver.resolve("JP", "Asia/Seoul")).isEqualTo("DE");
    }

    @Test
    @DisplayName("요청 스레드 밖(배치·sync 백필)에서도 타임존만으로 해석된다")
    void resolvesFromTimeZoneOutsideRequestThread() {
        assertThat(resolver.resolve(null, "Asia/Seoul")).isEqualTo("KR");
    }
}
