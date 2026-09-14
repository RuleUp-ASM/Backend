package com.ruleup.ruleup_backend;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 테스트용 MySQL 8.4 컨테이너.
 * @ServiceConnection 이 컨테이너의 접속 정보를 DataSource/Flyway에 자동 연결해주므로
 * 로컬에 MySQL을 따로 띄우지 않아도 @SpringBootTest가 실제 마이그레이션까지 돌린다.
 * (Docker 런타임 필요 — 로컬은 colima, CI 러너는 기본 제공)
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    MySQLContainer<?> mysqlContainer() {
        return new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));
    }

    /**
     * 앞으로 돌릴 수 있는 시계.
     *
     * <p>확정 배치는 「귀속일에서 D+2 가 지났는가」를 <b>거절 조건</b>으로 쓴다. 그 조건을
     * 시험하려면 오늘 만든 판정을 두고 「이틀 뒤」로 갈 수 있어야 한다 — 예전처럼 행의
     * {@code finalizeAfter} 를 과거로 당기는 우회는, 정작 막아야 할 <b>귀속일 기준 조기 확정</b>을
     * 그대로 통과시키므로 그 조건을 검증하지 못한다.
     *
     * <p>모든 IT 가 이 설정을 함께 들이므로 컨텍스트는 하나로 유지된다. 옮긴 테스트는
     * {@link MutableClock#reset()} 으로 되돌려 놓아야 한다.
     */
    @Bean
    @Primary
    MutableClock clock() {
        return new MutableClock();
    }

    /** 테스트가 앞뒤로 옮길 수 있는 {@link Clock}. 기본값은 시스템 시각이다. */
    public static class MutableClock extends Clock {

        private volatile Duration offset = Duration.ZERO;
        private final ZoneId zone;

        public MutableClock() { this(ZoneOffset.UTC); }

        private MutableClock(ZoneId zone) { this.zone = zone; }

        /** 시계를 앞으로 옮긴다. 되돌리려면 {@link #reset()}. */
        public void advance(Duration by) { this.offset = offset.plus(by); }

        /** 시스템 시각으로 되돌린다. 테스트가 끝나면 반드시 부른다. */
        public void reset() { this.offset = Duration.ZERO; }

        @Override public ZoneId getZone() { return zone; }

        @Override public Clock withZone(ZoneId other) {
            MutableClock copy = new MutableClock(other) {
                @Override public Instant instant() { return MutableClock.this.instant(); }
            };
            return copy;
        }

        @Override public Instant instant() { return Instant.now().plus(offset); }
    }
}
