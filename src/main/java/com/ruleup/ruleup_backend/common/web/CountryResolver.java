package com.ruleup.ruleup_backend.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Locale;

/**
 * 국가 코드(ISO 3166-1 alpha-2)를 "사용자 입력 없이" 현재 요청에서 서버가 해석한다.
 *
 * <p>우선순위:
 *  1) CDN/LB가 붙여주는 지오 헤더(CloudFront-Viewer-Country 등) — 가장 정확(실제 접속 국가).
 *  2) Accept-Language 헤더의 지역(getLocale().getCountry()) — 서블릿 네이티브 폴백.
 *     (헤더가 없으면 컨테이너 기본 로케일로 오염될 수 있어, 헤더가 실제로 있을 때만 사용한다.)
 *  3) 해석 불가면 null(추천은 채워진 세그먼트만 쓰므로 없어도 동작).
 *
 * <p>요청 스레드 밖(배치 등)에서 호출되면 요청이 없어 null을 돌려준다.
 */
@Component
public class CountryResolver {

    /** CDN/LB가 주입하는 지오 헤더 후보(있으면 최우선). */
    private static final String[] GEO_HEADERS = {
            "CloudFront-Viewer-Country",   // AWS CloudFront
            "X-Country-Code",              // 일반 LB/프록시 관례
            "X-AppEngine-Country"          // GAE
    };

    /** 현재 요청에서 국가 코드를 해석. 없으면 null. */
    public String resolve() {
        HttpServletRequest request = currentRequest();
        if (request == null) return null;

        for (String header : GEO_HEADERS) {
            String c = normalize(request.getHeader(header));
            if (c != null) return c;
        }

        // Accept-Language가 실제로 있을 때만 로케일 사용(없으면 서버 기본 로케일이라 신뢰 불가).
        String acceptLanguage = request.getHeader("Accept-Language");
        if (acceptLanguage != null && !acceptLanguage.isBlank()) {
            Locale locale = request.getLocale();
            if (locale != null) return normalize(locale.getCountry());
        }
        return null;
    }

    private HttpServletRequest currentRequest() {
        var attrs = RequestContextHolder.getRequestAttributes();
        return (attrs instanceof ServletRequestAttributes sra) ? sra.getRequest() : null;
    }

    /** 2자리 알파벳만 대문자 ISO 코드로 인정, 그 외(숫자/길이 불일치/빈값)는 null. */
    private String normalize(String code) {
        if (code == null) return null;
        String c = code.trim().toUpperCase();
        return (c.length() == 2 && c.chars().allMatch(Character::isLetter)) ? c : null;
    }
}
