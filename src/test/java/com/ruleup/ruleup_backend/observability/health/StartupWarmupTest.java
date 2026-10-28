package com.ruleup.ruleup_backend.observability.health;

import com.ruleup.ruleup_backend.observability.sli.SliFilter;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class StartupWarmupTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    @DisplayName("워밍업이 끝나기 전에는 헬스체크가 OUT_OF_SERVICE 다 — ALB 가 아직 트래픽을 보내지 않는다")
    void outOfServiceUntilWarmedUp() {
        StartupWarmup warmup = new StartupWarmup(true, 20, "api");

        assertThat(warmup.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
    }

    @Test
    @DisplayName("공개 경로를 합성·워밍업 헤더를 달고 돌아가며 부른 뒤 준비 완료가 된다")
    void warmsPublicPathsThenBecomesUp() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            boolean marked = exchange.getRequestHeaders().containsKey(SliFilter.SYNTHETIC_HEADER)
                    && exchange.getRequestHeaders().containsKey(StartupWarmup.HEADER);
            seen.add(exchange.getRequestURI().getPath() + (marked ? "" : " (헤더 없음)"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        StartupWarmup warmup = new StartupWarmup(true, 1, "api");

        warmup.warmUp(server.getAddress().getPort());

        assertThat(warmup.health().getStatus()).isEqualTo(Status.UP);
        assertThat(seen).contains("/api/v1/intro", "/api/v1/categories", PublicHealthController.PATH)
                .allSatisfy(s -> assertThat(s).doesNotContain("헤더 없음"));
    }

    @Test
    @DisplayName("서버가 응답하지 않아도 정해 둔 시간이 지나면 준비 완료로 넘어간다 — 배포가 막히지 않는다")
    void becomesUpEvenWhenRequestsFail() {
        StartupWarmup warmup = new StartupWarmup(true, 1, "api");

        warmup.warmUp(1);   // 아무도 듣지 않는 포트

        assertThat(warmup.isDone()).isTrue();
        assertThat(warmup.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    @DisplayName("공개 API 를 받지 않는 역할과 꺼 둔 설정은 처음부터 준비 완료다")
    void skippedWhenNotServingPublicApi() {
        assertThat(new StartupWarmup(true, 20, "admin").health().getStatus()).isEqualTo(Status.UP);
        assertThat(new StartupWarmup(true, 20, "migrate").health().getStatus()).isEqualTo(Status.UP);
        assertThat(new StartupWarmup(false, 20, "api").health().getStatus()).isEqualTo(Status.UP);
        assertThat(new StartupWarmup(true, 0, "api").health().getStatus()).isEqualTo(Status.UP);
    }
}
