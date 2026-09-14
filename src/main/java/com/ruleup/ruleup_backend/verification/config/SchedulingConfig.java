package com.ruleup.ruleup_backend.verification.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 배치 스케줄링 활성화(§1 확정 배치).
 *
 * <p><b>끌 수 있어야 한다.</b> 통합 테스트는 스프링 컨텍스트를 설정별로 여럿 띄우는데, 그 하나하나가
 * 자기 스케줄러를 돌린다 — 10초 주기 공지 팬아웃 같은 배치가 시험과 상관없이 끼어들고, 컨텍스트가
 * 닫히는 순간에도 이미 닫힌 커넥션 풀에 손을 뻗어 종료가 예외로 뒤덮이며 몇 분씩 늘어진다.
 * 배치의 동작은 각 시험이 메서드를 직접 불러 검증하므로, 자동 발화까지 필요하지는 않다.
 */
@Configuration
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@EnableScheduling
public class SchedulingConfig {
}
