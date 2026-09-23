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
 *
 * <h4>컨테이너는 JVM 당 하나다</h4>
 * 예전에는 {@code @Bean} 메서드가 매번 <b>새 컨테이너를 만들었다.</b> 스프링 테스트 컨텍스트는
 * 설정이 다르면 따로 캐시되므로, {@code @SpringBootTest(properties = ...)} 를 쓰는 클래스마다
 * 컨텍스트가 하나씩 늘고 <b>그만큼 MySQL 이 동시에 떴다</b> — 지금 그런 클래스가 아홉 개다.
 *
 * <p>그 상태에서 스위트 후반의 컨테이너가 기동에 실패했다. 단독으로 돌리면 통과하는데
 * 전체에서만 깨지는 형태였고, 실패 모양은 언제나 같았다 —
 * {@code ContainerLaunchException → CJCommunicationsException → EOFException}.
 * 열 개의 MySQL 을 동시에 띄울 이유가 애초에 없다.
 *
 * <p>그래서 인스턴스를 <b>공유</b>한다. {@code destroyMethod = ""} 가 필요하다 —
 * 컨테이너는 {@code AutoCloseable} 이라, 그냥 두면 <b>먼저 닫히는 컨텍스트가 컨테이너를
 * 멈춰</b> 아직 캐시에 살아 있는 다른 컨텍스트까지 끊어 버린다. 정리는 JVM 종료 때
 * Testcontainers 의 Ryuk 이 맡는다.
 *
 * <p>대신 모든 컨텍스트가 <b>같은 DB</b>를 본다. Flyway 는 두 번째 컨텍스트부터 적용할
 * 마이그레이션이 없어 그대로 지나가고, 테스트는 이미 유저·챌린지를 고유 이름으로 만들고 있어
 * 서로를 보지 않는다. 「전체를 세는」 단언만 이 전제에 기대므로, 그런 테스트는 자기 데이터로
 * 범위를 좁혀야 한다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /** JVM 당 하나. 정적 초기화에서 띄워, 어느 컨텍스트가 먼저 오든 이미 준비돼 있다. */
    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));

    static {
        MYSQL.start();
    }

    @Bean(destroyMethod = "")
    @ServiceConnection
    MySQLContainer<?> mysqlContainer() {
        return MYSQL;
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
