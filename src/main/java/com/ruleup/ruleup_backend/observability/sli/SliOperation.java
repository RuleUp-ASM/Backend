package com.ruleup.ruleup_backend.observability.sli;

import org.springframework.http.HttpMethod;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * 핵심 사용자 여정(CUJ)의 Operation — 노션 「알람 카탈로그」 §1 표 그대로다.
 *
 * <p>지표 차원은 URL 이 아니라 이 고정 이름이다. 목록을 바꾸면 SLO 계산기(infra/monitoring/lambdas)·배포 사전 점검의
 * Operation 목록·대시보드도 같이 바꾼다 — 원장은 {@code infra/monitoring/alarm-catalog.yaml} 의 {@code operations}.
 * 지연 목표는 「적격 요청의 95% 가 이 시간 안」이다.
 */
public enum SliOperation {
    OAUTH_GOOGLE("oauth_google", "C01", HttpMethod.POST, "/api/v1/auth/oauth/google", 3000),
    OAUTH_KAKAO("oauth_kakao", "C01", HttpMethod.POST, "/api/v1/auth/oauth/kakao", 3000),
    REFRESH("refresh", "C01", HttpMethod.POST, "/api/v1/auth/refresh", 1000),
    EXPLORE("explore", "C02", HttpMethod.GET, "/api/v1/challenges/explore", 1000),
    DETAIL("detail", "C02", HttpMethod.GET, "/api/v1/challenges/{challengeId}", 1000),
    JOIN("join", "C03", HttpMethod.POST, "/api/v1/challenges/{challengeId}/members", 1500),
    SYNC("sync", "C04", HttpMethod.POST, "/api/v1/verifications/sync", 3000),
    MANUAL("manual", "C04", HttpMethod.POST, "/api/v1/challenges/{challengeId}/verifications", 3000),
    TODAY("today", "C05", HttpMethod.GET, "/api/v1/challenges/{challengeId}/verifications/today", 1000),
    PROGRESS("progress", "C05", HttpMethod.GET, "/api/v1/verifications/progress", 1000);

    private final String id;
    private final String cuj;
    private final HttpMethod method;
    private final PathPattern pattern;
    private final long latencyTargetMillis;

    SliOperation(String id, String cuj, HttpMethod method, String path, long latencyTargetMillis) {
        this.id = id;
        this.cuj = cuj;
        this.method = method;
        this.pattern = PathPatternParser.defaultInstance.parse(path);
        this.latencyTargetMillis = latencyTargetMillis;
    }

    public String id() { return id; }

    public String cuj() { return cuj; }

    public long latencyTargetMillis() { return latencyTargetMillis; }

    /**
     * 메서드·경로로 Operation 을 찾는다. {@code /challenges/explore} 와 {@code /challenges/{challengeId}} 가 둘 다
     * 맞으므로 <b>리터럴 경로를 먼저</b> 본다(선언 순서가 곧 우선순위다 — EXPLORE 가 DETAIL 앞).
     */
    public static SliOperation match(String method, String path) {
        if (method == null || path == null) return null;
        var container = org.springframework.http.server.PathContainer.parsePath(path);
        for (SliOperation op : values()) {
            if (op.method.matches(method) && op.pattern.matches(container)) return op;
        }
        return null;
    }
}
