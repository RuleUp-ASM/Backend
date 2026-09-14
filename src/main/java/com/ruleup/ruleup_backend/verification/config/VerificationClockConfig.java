package com.ruleup.ruleup_backend.verification.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 판정 시각의 출처 (백엔드 4-3 · 7절 「D+2 이전 확정 0건」).
 *
 * <h4>왜 {@code Instant.now()} 를 그대로 쓰지 않는가</h4>
 * 확정 배치는 「지금이 귀속일의 D+2 를 지났는가」로 확정 여부를 가른다. 이 질문을 <b>막는 조건</b>으로
 * 쓰려면 통합 테스트가 「이틀 뒤」를 만들 수 있어야 하는데, {@code Instant.now()} 를 직접 부르면
 * 그 방법이 없다. 그래서 예전에는 테스트가 행의 {@code finalizeAfter} 를 과거로 당겨 확정을
 * 흉내 냈고, 그 우회를 성립시키려고 <b>운영 코드가 귀속일 기준 검사를 경고로만</b> 두어야 했다 —
 * 테스트의 편의가 스펙의 보장을 깎아 먹는 구조였다.
 *
 * <p>시각을 주입 가능한 자리로 빼면 그 교환이 사라진다. 테스트는 시계를 이틀 앞으로 돌리고,
 * 운영 코드는 귀속일에서 파생한 확정 시각을 <b>거절 조건</b>으로 쓸 수 있다.
 *
 * <p>운영에서는 시스템 시계 그대로다. {@code @ConditionalOnMissingBean} 이라 테스트가 자기
 * 시계를 올리면 그쪽이 이긴다.
 */
@Configuration
public class VerificationClockConfig {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock clock() {
        return Clock.systemUTC();
    }
}
