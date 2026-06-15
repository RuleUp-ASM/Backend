package com.ruleup.ruleup_backend;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

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
}
