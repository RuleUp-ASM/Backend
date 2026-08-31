package com.ruleup.ruleup_backend.applink;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * 앱링크의 <b>생성과 해석을 한 곳에서</b> 한다.
 *
 * <p>양쪽을 따로 두면 어느 한쪽만 고쳐졌을 때 "발급은 되는데 검사에서 떨어지는" 링크가 생긴다.
 * 그 버그는 사용자에게 "초대가 잘못됐습니다"로만 보여서 원인을 짚기가 특히 어렵다.
 *
 * <p>두 표현을 모두 받는다 — 웹 링크({@code https://…/c/{token}})와 커스텀 스킴
 * ({@code ruleup://c/{token}}). 안드로이드 앱링크는 도메인 검증이 실패하면 커스텀 스킴으로
 * 떨어지기 때문에 클라가 어느 쪽을 들고 올지 서버가 정할 수 없다.
 */
@Component
@RequiredArgsConstructor
public class AppLinks {

    /** 링크 경로는 {@code /{segment}/{token}} 두 조각 고정이다. */
    private static final int PATH_PARTS = 2;

    @Value("${app.app-links.base-url}")
    private String baseUrl;

    @Value("${app.app-links.scheme}")
    private String scheme;

    /** 링크를 만든다 — 초대 발급 경로가 문자열을 직접 조립하지 않도록. */
    public String build(AppLinkType type, String token) {
        return baseUrl + "/" + type.segment() + "/" + token;
    }

    /**
     * 링크를 해석한다. 우리 링크가 아니거나 경로 모양이 다르면 {@link Parsed#malformed()},
     * 우리 링크지만 모르는 타입이면 {@link Parsed#unsupported()} 다.
     *
     * <p>둘을 구분하는 이유: 전자는 클라가 잘못된 링크를 물고 온 것이고, 후자는 <b>구버전 앱이
     * 신버전 링크를 받은 상황</b>일 수 있다 — 안내 문구가 달라야 한다.
     */
    public Parsed parse(String url) {
        URI uri = toUri(url);
        if (uri == null || !isOurs(uri)) return Parsed.malformed();

        String path = uri.getPath();
        // 커스텀 스킴(ruleup://c/{token})은 호스트가 첫 조각이라 path 에 들어오지 않는다.
        if (isCustomScheme(uri)) path = "/" + uri.getHost() + (path == null ? "" : path);

        String[] parts = (path == null ? "" : path).split("/");
        // split 은 선행 "/" 때문에 빈 첫 조각을 만든다 → 유효한 링크는 ["", segment, token] 셋이다.
        if (parts.length != PATH_PARTS + 1 || parts[1].isBlank() || parts[2].isBlank())
            return Parsed.malformed();

        AppLinkType type = AppLinkType.ofSegment(parts[1]);
        if (type == null) return Parsed.unsupported();
        return new Parsed(type, parts[2], null);
    }

    private URI toUri(String url) {
        try {
            return new URI(url.trim());
        } catch (URISyntaxException | NullPointerException e) {
            return null;
        }
    }

    /** 우리가 발급한 링크인지 — 호스트가 우리 도메인이거나 커스텀 스킴이거나. */
    private boolean isOurs(URI uri) {
        if (isCustomScheme(uri)) return true;
        URI base = toUri(baseUrl);
        return base != null && base.getHost() != null
                && base.getHost().equalsIgnoreCase(uri.getHost());
    }

    private boolean isCustomScheme(URI uri) {
        return scheme.equalsIgnoreCase(uri.getScheme());
    }

    /**
     * 해석 결과. {@code type} 과 {@code token} 이 있으면 형식은 통과한 것이고,
     * 존재·만료 판정은 호출부가 이어서 한다.
     */
    public record Parsed(AppLinkType type, String token, AppLinkCheckReason failure) {

        static Parsed malformed() { return new Parsed(null, null, AppLinkCheckReason.MALFORMED); }

        static Parsed unsupported() { return new Parsed(null, null, AppLinkCheckReason.UNSUPPORTED); }

        public boolean isWellFormed() { return failure == null; }
    }
}
