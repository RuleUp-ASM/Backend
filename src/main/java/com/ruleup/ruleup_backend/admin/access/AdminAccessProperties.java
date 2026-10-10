package com.ruleup.ruleup_backend.admin.access;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 관리자 서비스의 Cloudflare Access 검증 설정 — {@code app.admin.access}.
 *
 * <p><b>모든 값이 비면 아무도 못 들어온다.</b> 허용 목록이 비었다고 「전원 허용」으로 해석하지 않는다.
 *
 * @param teamDomain     Access 팀 도메인({@code https://<team>.cloudflareaccess.com}). 발급자(iss) 검증과
 *                       공개키(JWKS) 주소에 함께 쓴다
 * @param audiences      허용할 Access 애플리케이션 AUD 태그. 토큰의 aud 가 이 중 하나와 맞아야 한다
 * @param allowedEmails  운영자 이메일. Access 정책과 <b>별개로</b> 서버가 한 번 더 거른다 —
 *                       정책을 잘못 넓혀도 여기 없는 사람은 못 들어온다
 * @param allowedOrigins 상태를 바꾸는 요청(POST·PUT·PATCH·DELETE)의 Origin 허용 목록(CSRF 방어)
 * @param jwksCacheSeconds 공개키 캐시 수명. 모르는 kid 가 오면 수명과 상관없이 한 번 다시 받는다
 */
@ConfigurationProperties(prefix = "app.admin.access")
public record AdminAccessProperties(String teamDomain,
                                    List<String> audiences,
                                    List<String> allowedEmails,
                                    List<String> allowedOrigins,
                                    Long jwksCacheSeconds) {

    public AdminAccessProperties {
        teamDomain = teamDomain == null ? "" : teamDomain.trim().replaceAll("/+$", "");
        audiences = clean(audiences, false);
        allowedEmails = clean(allowedEmails, true);
        allowedOrigins = clean(allowedOrigins, false).stream()
                .map(o -> o.replaceAll("/+$", "")).toList();
        if (jwksCacheSeconds == null || jwksCacheSeconds <= 0) jwksCacheSeconds = 3600L;
    }

    public boolean isConfigured() {
        return teamDomain.startsWith("https://") && !audiences.isEmpty() && !allowedEmails.isEmpty();
    }

    public String certsUrl() {
        return teamDomain + "/cdn-cgi/access/certs";
    }

    public Set<String> emailSet() {
        return Set.copyOf(allowedEmails);
    }

    private static List<String> clean(List<String> values, boolean lower) {
        if (values == null) return List.of();
        return values.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(String::trim)
                .map(v -> lower ? v.toLowerCase(Locale.ROOT) : v)
                .collect(Collectors.toUnmodifiableList());
    }
}
