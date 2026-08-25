package com.ruleup.ruleup_backend.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * 국가 코드(ISO 3166-1 alpha-2)를 현재 요청에서 서버가 해석한다.
 *
 * <p>우선순위:
 *  1) CDN/LB가 붙여주는 지오 헤더(CloudFront-Viewer-Country, CF-IPCountry 등) — 가장 정확(실제 접속 국가).
 *  2) 클라이언트가 보낸 기기 지역(deviceInfo.country) — 모바일에서 가장 신뢰할 수 있는 소스.
 *  3) Accept-Language 헤더의 지역(선호 로케일 중 지역이 있는 첫 값) — 헤더가 실제로 있을 때만.
 *  4) 기기 타임존(deviceInfo.timeZone, IANA) — 한 나라만 쓰는 타임존일 때만 인정.
 *  5) 해석 불가면 null.
 *
 * <p>1~3만 있던 시절 운영 DB의 {@code users.country_code}가 통째로 NULL로 남았다. ALB 직결 배포라 지오 헤더가
 * 없고, {@code deviceInfo.country}는 선택 필드라 클라가 안 보냈고, OkHttp는 {@code Accept-Language}를 기본으로
 * 붙이지 않기 때문이다. 그래서 안드 기기가 항상 들고 있는 <b>타임존</b>을 4순위로 넣고, 그래도 못 정하면
 * 호출부가 {@link #resolveFor(String, String, String)}로 서비스 기본 국가를 쓰게 한다.
 *
 * <p>요청 스레드 밖(배치 등)에서 호출되면 요청이 없어 클라이언트 값·타임존 폴백만 사용한다.
 */
@Component
public class CountryResolver {

    /** CDN/LB가 주입하는 지오 헤더 후보(있으면 최우선). */
    private static final String[] GEO_HEADERS = {
            "CloudFront-Viewer-Country",   // AWS CloudFront
            "CF-IPCountry",                // Cloudflare
            "Fastly-Client-Country",       // Fastly
            "X-Vercel-IP-Country",         // Vercel
            "X-Country-Code",              // 일반 LB/프록시 관례
            "X-Geo-Country",               // 일반 LB/프록시 관례
            "X-AppEngine-Country"          // GAE
    };


    /** 타임존 → 국가 표. tzdb zone.tab 에서 생성해 리소스로 고정한다(런타임 파일시스템 의존 없음). */
    private static final String ZONE_TABLE = "/tz-country.properties";

    /**
     * IANA 타임존 → 국가. tzdb의 zone.tab을 역인덱싱한 표를 리소스에서 읽는다.
     * 두 나라 이상이 함께 쓰는 타임존은 표에서 <b>제외</b>돼 있다 — 틀린 국가를 넣느니 비워 두는 편이 낫다.
     * {@code UTC}·{@code Etc/GMT+9}처럼 어느 나라에도 속하지 않는 ID도 자연히 빠진다.
     */
    private static final Map<String, String> ZONE_TO_COUNTRY = loadZoneToCountry();

    /** 어느 경로로도 국가를 못 정했을 때 쓸 서비스 기본 국가. 빈 값이면 기본값 없이 null 로 둔다. */
    private final String defaultCode;

    public CountryResolver() {
        this("KR");
    }

    @Autowired
    public CountryResolver(@Value("${app.country.default-code:KR}") String defaultCode) {
        this.defaultCode = normalizeStatic(defaultCode);
    }

    /** 클라이언트 값 없이 현재 요청만으로 해석(배치 등). */
    public String resolve() {
        return resolve(null, null);
    }

    /** @deprecated 타임존까지 넘기는 {@link #resolve(String, String)}을 쓴다. */
    @Deprecated
    public String resolve(String clientProvided) {
        return resolve(clientProvided, null);
    }

    /**
     * 현재 요청 + 클라이언트가 보낸 기기 지역·타임존으로 국가 코드를 해석. 없으면 null.
     *
     * @param clientProvided deviceInfo.country 등 클라이언트 제공값(ISO alpha-2 또는 "ko-KR" 형태 허용). null 가능.
     * @param ianaTimeZone   deviceInfo.timeZone 등 기기 타임존("Asia/Seoul"). null 가능.
     */
    public String resolve(String clientProvided, String ianaTimeZone) {
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

        // 4) 기기 타임존. 지오 헤더가 없는 배포에서 사실상 유일하게 항상 채워지는 소스다.
        return countryOfZone(ianaTimeZone);
    }

    /**
     * 저장할 최종 국가를 정한다 — 새 해석값 → 기존 값 → 서비스 기본값 순.
     *
     * <p>해석에 실패했다고 이미 알고 있던 국가를 지우거나 기본값으로 되돌리지 않는다.
     * 아무것도 모르는 신규 유저에게만 기본값이 적용돼, 컬럼이 NULL 로 남지 않는다.
     *
     * @param existing 지금 저장돼 있는 국가(없으면 null)
     */
    public String resolveFor(String existing, String clientProvided, String ianaTimeZone) {
        String resolved = resolve(clientProvided, ianaTimeZone);
        if (resolved != null) return resolved;
        String kept = normalize(existing);
        return (kept != null) ? kept : defaultCode;
    }

    /** 어느 경로로도 못 정했을 때 쓰는 서비스 기본 국가(설정으로 끌 수 있어 null 가능). */
    public String defaultCode() {
        return defaultCode;
    }

    private HttpServletRequest currentRequest() {
        var attrs = RequestContextHolder.getRequestAttributes();
        return (attrs instanceof ServletRequestAttributes sra) ? sra.getRequest() : null;
    }

    /** IANA 타임존 → 국가. 여러 나라가 공유하거나 국가가 없는 타임존은 null. */
    private String countryOfZone(String ianaTimeZone) {
        if (ianaTimeZone == null || ianaTimeZone.isBlank()) return null;
        String id = ianaTimeZone.trim();
        String direct = ZONE_TO_COUNTRY.get(id);
        if (direct != null) return direct;
        try {
            return ZONE_TO_COUNTRY.get(ZoneId.of(id, ZoneId.SHORT_IDS).getId());
        } catch (DateTimeException e) {
            return null;   // 형식 오류·알 수 없는 ID
        }
    }

    /**
     * ISO 3166-1 alpha-2 로 정규화. 2자리 알파벳이면 대문자로 인정.
     * "ko-KR"/"ko_KR"/"en-US" 처럼 구분자가 있으면 마지막 조각(지역)을 추출해 인정.
     * 그 외(숫자/길이 불일치/빈값)는 null.
     */
    private String normalize(String code) {
        return normalizeStatic(code);
    }

    private static String normalizeStatic(String code) {
        if (code == null) return null;
        String c = code.trim();
        int sep = Math.max(c.lastIndexOf('-'), c.lastIndexOf('_'));
        if (sep >= 0 && sep + 1 < c.length()) c = c.substring(sep + 1);   // 로케일 → 지역 조각
        c = c.toUpperCase();
        return (c.length() == 2 && c.chars().allMatch(Character::isLetter)) ? c : null;
    }

    private static Map<String, String> loadZoneToCountry() {
        Properties table = new Properties();
        try (InputStream in = CountryResolver.class.getResourceAsStream(ZONE_TABLE)) {
            if (in == null) return Map.of();   // 표가 없으면 타임존 폴백만 비활성 — 나머지 경로는 그대로 동작
            table.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Map.of();
        }
        Map<String, String> zones = new HashMap<>(table.size());
        for (String zone : table.stringPropertyNames()) {
            String country = normalizeStatic(table.getProperty(zone));
            if (country != null) zones.put(zone, country);
        }
        return Map.copyOf(zones);
    }
}
