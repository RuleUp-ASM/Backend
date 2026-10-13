package com.ruleup.ruleup_backend.observability.sli;

import com.ruleup.ruleup_backend.observability.metric.MetricContext;
import com.ruleup.ruleup_backend.observability.metric.MetricKey;
import com.ruleup.ruleup_backend.observability.metric.MinuteMetricBuffer;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * SLI 원천 카운터 — {@code RuleUp/SLI} 의 {@code EligibleCount · AvailabilityBadCount · LatencyBadCount}
 * (차원 {@code Env, Service, Operation}) 와 진단용 {@code DurationMs} 분포.
 *
 * <p>노션 「알람 카탈로그」 §1·§8: 지연 SLI 의 분자는 「가용성 실패 또는 목표 시간을 넘긴 적격 요청」이다.
 * 세 카운터는 공개 API 태스크에서 <b>요청이 없는 분에도 0 을 보낸다</b> — SLO 계산기가 0 요청을 「측정 불가」로,
 * 배포 사전 점검이 빈 시계열을 「계측 누락」으로 판정하는 근거다.
 *
 * <p>같은 필터에서 처리량 원천({@code RuleUp/Traffic})도 함께 센다 — 로그인 완료·sync 수신(FLOW-01/02)과
 * 상태별 4xx 거절 수(HTTP-04). 이것들은 제품 참여 지표가 아니라 서버가 실제로 받아 처리한 건수다.
 *
 * <p>{@code ApiRequestCount} 는 합성 점검을 뺀 {@code /api/**} 요청 수다 — 배포 사전 점검이 「API 요청은 들어오는데
 * CUJ 원천이 0」(라우트 계측 누락)을 가르는 기준. ALB 요청 수는 경로를 못 나눠 Route 53·외부 점검의
 * {@code /health/public} 호출(분당 십수 건)이 섞이고, 트래픽이 적을 때 늘 계측 누락으로 잘못 걸렸다.
 */
@Component
public class SliRecorder {

    public static final String NAMESPACE = "RuleUp/SLI";
    public static final String TRAFFIC = "RuleUp/Traffic";
    /** HTTP-04 가 따로 보는 거절 상태. 그 밖의 4xx 는 대시보드 로그 표(client_error)로 본다. */
    static final int[] REJECT_STATUSES = {400, 401, 403, 429};

    private final MinuteMetricBuffer buffer;
    private final Map<SliOperation, Keys> keys = new EnumMap<>(SliOperation.class);
    private final MetricKey loginCompleted;
    private final MetricKey syncReceived;
    private final MetricKey apiRequests;
    private final Map<Integer, MetricKey> rejects = new java.util.HashMap<>();

    private record Keys(MetricKey eligible, MetricKey bad, MetricKey latencyBad, MetricKey duration) {}

    public SliRecorder(MinuteMetricBuffer buffer, MetricContext ctx) {
        this.buffer = buffer;
        boolean publicApi = ctx.role().servesPublicApi();
        for (SliOperation op : SliOperation.values()) {
            Map<String, String> d = ctx.dims("Operation", op.id());
            Keys k = new Keys(MetricKey.count(NAMESPACE, "EligibleCount", d),
                    MetricKey.count(NAMESPACE, "AvailabilityBadCount", d),
                    MetricKey.count(NAMESPACE, "LatencyBadCount", d),
                    new MetricKey(NAMESPACE, "DurationMs", d, MetricKey.Unit.MILLISECONDS));
            keys.put(op, k);
            if (publicApi) {
                buffer.declare(k.eligible());
                buffer.declare(k.bad());
                buffer.declare(k.latencyBad());
            }
        }
        loginCompleted = MetricKey.count(TRAFFIC, "LoginCompletedCount", ctx.dims());
        syncReceived = MetricKey.count(TRAFFIC, "SyncReceivedCount", ctx.dims());
        apiRequests = MetricKey.count(TRAFFIC, "ApiRequestCount", ctx.dims());
        for (int status : REJECT_STATUSES) {
            rejects.put(status, MetricKey.count(TRAFFIC, "HttpRejectCount", ctx.dims("Status", String.valueOf(status))));
        }
        if (publicApi) {
            buffer.declare(loginCompleted);
            buffer.declare(syncReceived);
            buffer.declare(apiRequests);
            rejects.values().forEach(buffer::declare);
        }
    }

    /** 합성 점검이 아닌 {@code /api/**} 요청 한 건 — CUJ 여부와 상관없이 센다. */
    public void recordApiRequest() {
        buffer.increment(apiRequests);
    }

    /** 응답이 끝난 요청 한 건. {@code op} 가 null 이면 CUJ 밖이라 거절 수만 센다. */
    public void record(SliOperation op, int status, SliOutcome outcome, long durationMillis) {
        MetricKey reject = rejects.get(status);
        if (reject != null) buffer.increment(reject);
        if (op == null || outcome == SliOutcome.EXCLUDED) return;

        Keys k = keys.get(op);
        buffer.increment(k.eligible());
        buffer.observe(k.duration(), durationMillis);
        if (outcome == SliOutcome.BAD) {
            buffer.increment(k.bad());
            buffer.increment(k.latencyBad());
        } else {
            if (durationMillis > op.latencyTargetMillis()) buffer.increment(k.latencyBad());
            if (op == SliOperation.OAUTH_GOOGLE || op == SliOperation.OAUTH_KAKAO) buffer.increment(loginCompleted);
            if (op == SliOperation.SYNC) buffer.increment(syncReceived);
        }
    }
}
