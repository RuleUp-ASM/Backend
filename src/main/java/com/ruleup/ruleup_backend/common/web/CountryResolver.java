package com.ruleup.ruleup_backend.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collections;
import java.util.Locale;

/**
 * 국가 코드(ISO 3166-1 alpha-2)를 현재 요청에서 서버가 해석한다.
 *
 * <p>우선순위:
 *  1) CDN/LB가 붙여주는 지오 헤더(CloudFront-Viewer-Country 등) — 가장 정확(실제 접속 국가).
 *  2) 클라이언트가 보낸 기기 지역(deviceInfo.country) — 모바일에서 가장 신뢰할 수 있는 소스.
 *     CDN 지오 헤더가 없는 배포(직접/ALB-only)에서도 국가가 채워지도록 한다.
 *  3) Accept-Language 헤더의 지역(선호 로케일 중 지역이 있는 첫 값) — 헤더가 실제로 있을 때만.
 *  4) 해석 불가면 null(추천은 채워진 세그먼트만 쓰므로 없어도 동작).
 *
 * <p>요청 스레드 밖(배치 등)에서 호출되면 요청이 없어 클라이언트 값 폴백만 사용한다.
 */
@Component
public class CountryResolver {

    /** CDN/LB가 주입하는 지오 헤더 후보(있으면 최우선). */
    private static final String[] GEO_HEADERS = {
            "CloudFront-Viewer-Country",   // AWS CloudFront
            "X-Country-Code",              // 일반 LB/프록시 관례
            "X-AppEngine-Country"          // GAE
    };

    /** 클라이언트 값 없이 현재 요청만으로 해석(배치 등). */
    public String resolve() {
        return resolve(null);
    }

    /**
     * 현재 요청 + 클라이언트가 보낸 기기 지역으로 국가 코드를 해석. 없으면 null.
     * @param clientProvided deviceInfo.country 등 클라이언트 제공값(ISO alpha-2 또는 "ko-KR" 형태 허용). null 가능.
     */
    public String resolve(String clientProvided) {
        HttpServletRequest request = currentRequest();

        // 1) CDN/LB 지오 헤더(실제 접속 국가) 최우선.
        if (request != null) {
            for (String header : GEO_HEADERS) {
                String c = normalize(request.getHeader(header));
                if (c != null) return c;
            }
        }

        // 2) 클라이언트 제공 기기 지역(모바일 신뢰 소스). "ko-KR"/"ko_KR" 형태도 지역만 추출.
        String fromClient = normalize(clientProvided);
        if (fromClient != null) return fromClient;

        // 3) Accept-Language가 실제로 있을 때만, 선호 로케일 중 "지역이 있는" 첫 값 사용.
        if (request != null) {
            String acceptLanguage = request.getHeader("Accept-Language");
            if (acceptLanguage != null && !acceptLanguage.isBlank()) {
                for (Locale locale : Collections.list(request.getLocales())) {
                    String c = normalize(locale.getCountry());
                    if (c != null) return c;   // 언어만 있는 "ko"는 지역이 없어 건너뜀
                }
            }
        }
        return null;
    }

    private HttpServletRequest currentRequest() {
        var attrs = RequestContextHolder.getRequestAttributes();
        return (attrs instanceof ServletRequestAttributes sra) ? sra.getRequest() : null;
    }

    /**
     * ISO 3166-1 alpha-2 로 정규화. 2자리 알파벳이면 대문자로 인정.
     * "ko-KR"/"ko_KR"/"en-US" 처럼 구분자가 있으면 마지막 조각(지역)을 추출해 인정.
     * 그 외(숫자/길이 불일치/빈값)는 null.
     */
    private String normalize(String code) {
        if (code == null) return null;
        String c = code.trim();
        int sep = Math.max(c.lastIndexOf('-'), c.lastIndexOf('_'));
        if (sep >= 0 && sep + 1 < c.length()) c = c.substring(sep + 1);   // 로케일 → 지역 조각
        c = c.toUpperCase();
        return (c.length() == 2 && c.chars().allMatch(Character::isLetter)) ? c : null;
    }
}
