package com.ruleup.ruleup_backend.admin.access;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 관리자 서비스 컨텍스트를 <b>운영과 같은 제한 계정 {@code ruleup_admin}</b> 으로 붙인다.
 *
 * <p>권한은 {@code infra/admin-split/db/grants.sql} 을 그대로 실행해 만든다 — 운영에 적용할 SQL 과 시험이 쓰는 SQL 이
 * 같다. 그래서 이 컨텍스트에서 관리자 기능이 통과한다는 것은 「그 권한으로 충분하다」는 증거이고, 권한 밖의 테이블을
 * 건드리면 MySQL 이 거부해 시험이 깨진다.
 *
 * <p>스키마는 루트로 먼저 만든다(관리자 계정에는 DDL 이 없다). 시험 데이터 준비도 {@link #root()} 로 한다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RestrictedAdminDb {

    static final String USER = "ruleup_admin";
    static final String PASSWORD = "admin-test-pw-1";

    static {
        MySQLContainer<?> mysql = TestcontainersConfiguration.sharedMysql();
        Flyway.configure().dataSource(mysql.getJdbcUrl(), "root", mysql.getPassword())
                .locations("classpath:db/migration").load().migrate();
        JdbcTemplate root = root();
        String sql;
        try {
            sql = Files.readString(Path.of("infra/admin-split/db/grants.sql"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        sql = sql.replace("__SCHEMA__", mysql.getDatabaseName()).replace("__REQUIRE_SSL__", "")
                .replace("${ADMIN_DB_PASSWORD}", PASSWORD)
                .replace("${APP_DB_PASSWORD}", "app-test-pw-1")
                .replace("${MIGRATOR_DB_PASSWORD}", "migrator-test-pw-1");
        for (String statement : sql.split(";\n")) {
            String s = statement.lines().filter(l -> !l.startsWith("--")).reduce("", (a, b) -> a + "\n" + b).trim();
            if (s.isEmpty() || s.startsWith("SHOW")) continue;
            root.execute(s);
        }
    }

    /** 시험 데이터 준비용 — 관리자 계정이 못 쓰는 테이블에 행을 넣을 때. */
    public static JdbcTemplate root() {
        MySQLContainer<?> mysql = TestcontainersConfiguration.sharedMysql();
        return new JdbcTemplate(new DriverManagerDataSource(mysql.getJdbcUrl(), "root", mysql.getPassword()));
    }

    @Bean
    static BeanPostProcessor restrictedAdminCredentials() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String name) {
                if (bean instanceof HikariDataSource ds) {
                    ds.setUsername(USER);
                    ds.setPassword(PASSWORD);
                }
                return bean;
            }
        };
    }
}
