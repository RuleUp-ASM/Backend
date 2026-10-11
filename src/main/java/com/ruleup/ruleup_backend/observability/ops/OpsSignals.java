package com.ruleup.ruleup_backend.observability.ops;

import com.ruleup.ruleup_backend.observability.metric.MetricContext;
import com.ruleup.ruleup_backend.observability.metric.MetricKey;
import com.ruleup.ruleup_backend.observability.metric.MinuteMetricBuffer;
import org.springframework.stereotype.Component;

/**
 * 도메인 코드가 운영 경보 원천을 남기는 한 곳 — 노션 「알람 카탈로그」 §5·§6 의 앱 쪽 지표.
 *
 * <ul>
 *   <li>{@code RuleUp/Ops ProviderCallCount · ProviderFailureCount}(Feature, Provider) — EXT-01. <b>공급자 호출 시도마다</b>
 *       센다(재시도·폴백도 각각). 실패에는 전송 실패·timeout·5xx·429·쿼터 거절이 모두 들어간다</li>
 *   <li>{@code RuleUp/Ops LlmLatencyMs}(Feature) 원값 분포 — EXT-02 p95</li>
 *   <li>{@code RuleUp/Ops InvalidTokenCount}(Provider=fcm) — TOKEN-01 집단 토큰 무효화</li>
 *   <li>{@code RuleUp/Traffic PushAttemptCount} — FLOW-03 발송 처리량(0 도 매분 보낸다)</li>
 *   <li>{@code RuleUp/Ops CacheLookupCount · CacheHitCount}(CacheKind) — CACHE-01</li>
 *   <li>{@code RuleUp/Ops InvariantViolationCount}(Invariant) — INV-01. 정상적인 중복·멱등 재시도는 넣지 않는다</li>
 *   <li>{@code RuleUp/Security AdminLoginFailedCount · RefreshReuseDetectedCount} — SEC-01·SEC-05. 토큰 값은 남기지 않는다</li>
 * </ul>
 * 차원 값은 모두 고정 열거다(사용자·챌린지 ID 없음).
 */
@Component
public class OpsSignals {

    public static final String OPS = "RuleUp/Ops";
    public static final String SECURITY = "RuleUp/Security";
    public static final String TRAFFIC = "RuleUp/Traffic";

    /** 코드로 정의한 불변식 — 새로 늘릴 때는 경보 차원도 같이 는다. */
    public enum Invariant { SCORE_LEDGER }

    private final MinuteMetricBuffer buffer;
    private final MetricContext ctx;
    private final MetricKey pushAttempts;
    private final MetricKey adminLoginFailed;
    private final MetricKey refreshReuse;

    public OpsSignals(MinuteMetricBuffer buffer, MetricContext ctx) {
        this.buffer = buffer;
        this.ctx = ctx;
        this.pushAttempts = MetricKey.count(TRAFFIC, "PushAttemptCount", ctx.dims());
        this.adminLoginFailed = MetricKey.count(SECURITY, "AdminLoginFailedCount", ctx.dims());
        this.refreshReuse = MetricKey.count(SECURITY, "RefreshReuseDetectedCount", ctx.dims());
        if (ctx.role().runsBackgroundWork()) buffer.declare(pushAttempts);
    }

    /** 외부 공급자 호출 한 번. {@code durationMillis} 가 음수면 지연 분포에 넣지 않는다. */
    public void providerCall(String feature, String provider, boolean failed, long durationMillis) {
        var d = ctx.dims("Feature", feature, "Provider", provider);
        buffer.increment(MetricKey.count(OPS, "ProviderCallCount", d));
        if (failed) buffer.increment(MetricKey.count(OPS, "ProviderFailureCount", d));
        if (durationMillis >= 0 && !"fcm".equals(provider)) {
            buffer.observe(new MetricKey(OPS, "LlmLatencyMs", ctx.dims("Feature", feature), MetricKey.Unit.MILLISECONDS),
                    durationMillis);
        }
    }

    /** FCM 토큰 단위 결과 묶음 — 시도·서버 오류·죽은 토큰. */
    public void pushTokens(int attempted, int serverErrors, int invalidTokens) {
        if (attempted <= 0) return;
        buffer.add(pushAttempts, attempted);
        var d = ctx.dims("Feature", "push", "Provider", "fcm");
        buffer.add(MetricKey.count(OPS, "ProviderCallCount", d), attempted);
        if (serverErrors > 0) buffer.add(MetricKey.count(OPS, "ProviderFailureCount", d), serverErrors);
        if (invalidTokens > 0) buffer.add(MetricKey.count(OPS, "InvalidTokenCount", ctx.dims("Provider", "fcm")), invalidTokens);
    }

    public void cacheLookup(String kind, boolean hit) {
        var d = ctx.dims("CacheKind", kind);
        buffer.increment(MetricKey.count(OPS, "CacheLookupCount", d));
        if (hit) buffer.increment(MetricKey.count(OPS, "CacheHitCount", d));
    }

    public void invariantViolated(Invariant invariant, long count) {
        if (count <= 0) return;
        buffer.add(MetricKey.count(OPS, "InvariantViolationCount", ctx.dims("Invariant", invariant.name())), count);
    }

    public void adminLoginFailed() {
        buffer.increment(adminLoginFailed);
    }

    public void refreshReuseDetected() {
        buffer.increment(refreshReuse);
    }
}
