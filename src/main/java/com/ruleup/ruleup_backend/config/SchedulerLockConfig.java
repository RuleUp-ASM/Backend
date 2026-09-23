package com.ruleup.ruleup_backend.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import static net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock.InterceptMode.PROXY_SCHEDULER;

/**
 * 배치 분산 락. {@code @SchedulerLock} 이 붙은 배치는 태스크가 여럿이어도 한 번에 하나만 돈다.
 *
 * <p><b>스케줄러가 부를 때만 잠근다</b>({@code PROXY_SCHEDULER}). 메서드 프록시 방식은 락을 못 잡은
 * 호출에 null 을 돌려주는데, {@code int} 를 돌려주는 배치가 아홉 개라 건너뛸 때마다 예외가 된다.
 * 또 시험과 운영자 수동 실행이 같은 메서드를 직접 부를 때 락에 막히지 않아야 한다. 스케줄러의
 * Runnable 을 감싸므로 락은 자연히 트랜잭션보다 바깥에 있다 — 커밋이 끝난 뒤에 풀린다.
 * ⚠️ ShedLock 7 에서 이 모드는 「제거 예정」이다. 제거된 버전으로 올릴 때는 메서드 프록시로 바꾸고,
 * {@code int} 를 돌려주는 배치에 void 진입점을 따로 두어야 한다(SchedulerLockIT 가 깨져서 알려 준다).
 *
 * <p>시각은 DB 시계로 비교한다({@code usingDbTime}). 태스크마다 시계가 조금씩 어긋나도 누가 락을
 * 가졌는지의 판정이 갈리지 않는다.
 *
 * <p>락을 달지 않는 배치 — 태스크마다 돌아야 하는 것들이다.
 * <ul>
 *   <li>SQS 소비자({@code *ModerationQueue.poll}): 메시지는 큐가 한 소비자에게만 준다.</li>
 *   <li>태스크별 지표·게이지({@code SystemMetricsSampler.sample}, {@code OutboxMetrics.refresh},
 *       {@code WatcherHealth.sample}): 락을 걸면 나머지 태스크의 게이지가 멈춘다.</li>
 *   <li>로컬 캐시 무효화({@code SegmentScoreService.evictLocalCaches}).</li>
 * </ul>
 * 새 {@code @Scheduled} 는 둘 중 하나로 분류돼야 한다 — SchedulerLockCoverageTest 가 강제한다.
 */
@Configuration
@ConditionalOnProperty(name = "app.scheduling.lock.enabled", havingValue = "true", matchIfMissing = true)
@EnableSchedulerLock(interceptMode = PROXY_SCHEDULER, defaultLockAtMostFor = "PT10M")
public class SchedulerLockConfig {

    @Bean
    public LockProvider lockProvider(JdbcTemplate jdbcTemplate) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(jdbcTemplate)
                .withTableName("shedlock")
                .usingDbTime()
                .build());
    }
}
