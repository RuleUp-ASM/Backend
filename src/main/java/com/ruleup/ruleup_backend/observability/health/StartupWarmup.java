package com.ruleup.ruleup_backend.observability.health;

import com.ruleup.ruleup_backend.config.runtime.RuntimeRole;
import com.ruleup.ruleup_backend.observability.sli.SliFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * 기동 워밍업 — 새 태스크가 ALB 트래픽을 받기 전에 JVM 을 데운다.
 *
 * <p>롤링 배포에서 옛 태스크가 빠지고 새 태스크만 트래픽을 받는 2~3분 동안 응답이 느려졌다
 * (prod 2026-10-23 20:55~20:57: p95 3~10ms → 438ms, 최대 1.9초, 5xx 0). 새 JVM 은 첫 요청들에서
 * 클래스 로딩·JIT 컴파일·커넥션 준비를 치른다. 기동 직후 이 프로세스가 자기 자신에게 공개 요청을 반복해 보내
 * 그 비용을 먼저 치르고, 끝날 때까지 헬스체크를 {@code OUT_OF_SERVICE}(503)로 둬서 ALB 가 아직
 * 트래픽을 보내지 않게 한다. 롤링 배포는 새 태스크가 헬스체크를 넘길 때까지 옛 태스크를 내리지 않는다.
 *
 * <p>로그인이 필요한 API 는 부르지 않는다 — 토큰 없이는 컨트롤러까지 가지 못하고, 401 은 거절 지표(HTTP-04)를
 * 올린다. 공개 경로로도 필터·MVC·Jackson·JPA·JDBC 공통 경로는 데워진다. 워밍업 요청은 합성 점검 헤더를 달아
 * SLI 에서 빠지고, {@value #HEADER} 헤더로 접근 로그에서도 빠진다.
 *
 * <p>어떤 경우에도 정해 둔 시간이 지나면 준비 완료로 넘긴다 — 워밍업이 막혀 헬스체크를 영영 못 넘기면 배포가
 * 되돌려진다. 공개 API 를 받지 않는 역할(관리자·마이그레이션)은 데우지 않는다.
 */
@Component
public class StartupWarmup implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(StartupWarmup.class);

    /** 접근 로그(RequestLogFilter)가 건너뛰는 표시. */
    public static final String HEADER = "X-RuleUp-Warmup";

    /** 부작용 없는 공개 GET — 인트로는 설정만, 카테고리는 DB 를 읽는다. */
    static final List<Target> TARGETS = List.of(
            new Target("/api/v1/intro", true),
            new Target("/api/v1/categories", false),
            new Target(PublicHealthController.PATH, false));

    record Target(String path, boolean introHeaders) {}

    private final boolean enabled;
    private final Duration duration;
    private final boolean servesPublicApi;
    private volatile boolean done;

    public StartupWarmup(@Value("${app.warmup.enabled:true}") boolean enabled,
                         @Value("${app.warmup.duration-seconds:20}") int durationSeconds,
                         @Value("${app.runtime.role:api}") String runtimeRole) {
        this.enabled = enabled;
        this.duration = Duration.ofSeconds(Math.max(durationSeconds, 0));
        this.servesPublicApi = RuntimeRole.valueOf(runtimeRole.toUpperCase()).servesPublicApi();
        this.done = !enabled || !servesPublicApi || durationSeconds <= 0;
    }

    @Override
    public Health health() {
        return done ? Health.up().build() : Health.outOfService().withDetail("warmup", "running").build();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady(ApplicationReadyEvent event) {
        if (done) return;
        if (!(event.getApplicationContext() instanceof WebServerApplicationContext web)
                || web.getWebServer() == null || web.getWebServer().getPort() <= 0) {
            done = true;   // 서블릿 서버 없이 뜬 컨텍스트(시험 등) — 데울 대상이 없다
            return;
        }
        int port = web.getWebServer().getPort();
        Thread worker = new Thread(() -> warmUp(port), "startup-warmup");
        worker.setDaemon(true);
        worker.start();
    }

    /** 정해 둔 시간 동안 공개 경로를 돌아가며 부른다. 끝나면(실패해도) 준비 완료다. */
    void warmUp(int port) {
        long started = System.nanoTime();
        long deadline = started + duration.toNanos();
        int sent = 0;
        int failed = 0;
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        try {
            while (System.nanoTime() < deadline) {
                for (Target t : TARGETS) {
                    try {
                        HttpResponse<Void> res = client.send(request(port, t), HttpResponse.BodyHandlers.discarding());
                        if (res.statusCode() >= 400) failed++;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (Exception e) {
                        failed++;
                    }
                    sent++;
                }
            }
        } finally {
            done = true;
            log.info("startup_warmup_done sent={} failed={} elapsedMs={}",
                    sent, failed, (System.nanoTime() - started) / 1_000_000L);
        }
    }

    private static HttpRequest request(int port, Target t) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + t.path()))
                .timeout(Duration.ofSeconds(5))
                .header(SliFilter.SYNTHETIC_HEADER, "warmup")
                .header(HEADER, "1")
                .GET();
        if (t.introHeaders()) b.header("platform", "ANDROID").header("appVersionCode", "1");
        return b.build();
    }

    boolean isDone() { return done; }
}
