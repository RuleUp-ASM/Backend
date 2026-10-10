package com.ruleup.ruleup_backend.config.runtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * 마이그레이션 태스크({@code app.runtime.role=migrate})의 끝 — Flyway 는 컨텍스트가 뜨는 도중에 이미 돌았다.
 * 여기까지 왔다는 것은 성공했다는 뜻이므로 0 으로 종료한다. 실패하면 컨텍스트가 뜨지 못해 0 이 아닌 값으로
 * 끝나고, 배포 워크플로가 그 종료 코드를 보고 서비스 갱신을 멈춘다.
 */
@Slf4j
@Component
@ConditionalOnExpression("'${app.runtime.role:api}'.equalsIgnoreCase('migrate')")
public class MigrationTaskRunner implements ApplicationRunner {

    private final ConfigurableApplicationContext context;

    public MigrationTaskRunner(ConfigurableApplicationContext context) {
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("schema_migration_completed — 마이그레이션 태스크를 종료한다");
        System.exit(SpringApplication.exit(context, () -> 0));
    }
}
