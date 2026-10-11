package com.ruleup.ruleup_backend.observability.ops;

import com.ruleup.ruleup_backend.config.runtime.RuntimeRole;
import com.ruleup.ruleup_backend.config.runtime.RuntimeRoleProperties;
import com.ruleup.ruleup_backend.observability.metric.MetricContext;
import com.ruleup.ruleup_backend.observability.metric.MinuteMetricBuffer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 2026-10-12 stg 배포 실패의 회귀 시험 — 기동 중(서비스 커넥터를 떼어 둔 사이) 샘플러가 돌아도 커넥터를 만들지 않아야 한다.
 * {@code Tomcat#getConnector()} 는 이때 기본 커넥터를 새로 만들어 포트를 잡고, 진짜 웹서버가 「Port already in use」로 죽었다.
 */
class RuntimeOpsSamplerTomcatTest {

    @Test
    @DisplayName("기동 전 Tomcat 에 샘플러가 돌아도 커넥터를 새로 만들지 않는다")
    @SuppressWarnings("unchecked")
    void doesNotCreateConnectorDuringStartup() throws Exception {
        Tomcat tomcat = new Tomcat();
        tomcat.setBaseDir(Files.createTempDirectory("tomcat").toString());
        Connector connector = new Connector("HTTP/1.1");
        connector.setPort(0);
        tomcat.getService().addConnector(connector);
        tomcat.getHost().setAutoDeploy(false);
        tomcat.addContext("", Files.createTempDirectory("ctx").toString());   // 부트가 찾는 Context 하나
        TomcatWebServer server = new TomcatWebServer(tomcat, false);   // 부트처럼 초기화만 — 이때 서비스 커넥터를 떼어 둔다
        try {
            assertThat(tomcat.getService().findConnectors()).as("기동 전에는 커넥터가 없다").isEmpty();
            WebServerApplicationContext context = mock(WebServerApplicationContext.class);
            when(context.getWebServer()).thenReturn(server);
            RuntimeOpsSampler sampler = new RuntimeOpsSampler(new MinuteMetricBuffer(Clock.systemUTC()),
                    new MetricContext("stg", new RuntimeRoleProperties(RuntimeRole.API, false)),
                    mock(ObjectProvider.class), new SimpleMeterRegistry(), context);

            sampler.sample();

            assertThat(tomcat.getService().findConnectors()).as("샘플러가 커넥터를 만들면 진짜 웹서버가 포트를 못 잡는다").isEmpty();
        } finally {
            server.destroy();
        }
    }
}
