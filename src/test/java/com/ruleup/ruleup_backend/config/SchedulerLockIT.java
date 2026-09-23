package com.ruleup.ruleup_backend.config;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.ScheduledMethodRunnable;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 스케줄러를 거친 배치가 실제 MySQL 의 shedlock 행으로 잠기는지 본다.
 *
 * <p>태스크 두 개를 흉내 내는 대신, 같은 배치를 스케줄러에 연달아 두 번 올린다 — 첫 실행이
 * 끝난 뒤에도 lockAtLeastFor 동안은 락이 남아 있어야 두 번째가 건너뛴다. 이게 운영에서
 * 「같은 분(分)에 발화한 두 태스크 중 늦은 쪽이 먼저 끝난 쪽을 따라 또 도는」 경우를 막는 장치다.
 */
@Import({TestcontainersConfiguration.class, SchedulerLockIT.Jobs.class})
@SpringBootTest
class SchedulerLockIT {

    @TestConfiguration
    static class Jobs {
        @Bean
        CountingJob countingJob() { return new CountingJob(); }

        @Bean
        TaskScheduler lockTestScheduler() {
            ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
            s.setPoolSize(2);
            s.setThreadNamePrefix("lock-it-");
            s.initialize();
            return s;
        }
    }

    static class CountingJob {
        final AtomicInteger runs = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);

        @SchedulerLock(name = "SchedulerLockIT.run", lockAtMostFor = "PT1M", lockAtLeastFor = "PT30S")
        public void run() {
            runs.incrementAndGet();
            done.countDown();
        }
    }

    @Autowired CountingJob job;
    @Autowired TaskScheduler lockTestScheduler;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("같은 배치를 연달아 발화하면 lockAtLeastFor 동안 두 번째는 건너뛴다")
    void secondFiringWithinLockAtLeastForIsSkipped() throws Exception {
        jdbc.update("DELETE FROM shedlock WHERE name = 'SchedulerLockIT.run'");
        Runnable firing = new ScheduledMethodRunnable(job, CountingJob.class.getMethod("run"));

        lockTestScheduler.schedule(firing, Instant.now());
        assertThat(job.done.await(10, TimeUnit.SECONDS)).isTrue();

        job.done = new CountDownLatch(1);
        lockTestScheduler.schedule(firing, Instant.now());
        assertThat(job.done.await(2, TimeUnit.SECONDS)).as("두 번째 발화는 돌지 않아야 한다").isFalse();

        assertThat(job.runs).hasValue(1);
        assertThat(jdbc.queryForObject(
                "SELECT lock_until > UTC_TIMESTAMP(3) FROM shedlock WHERE name = 'SchedulerLockIT.run'", Boolean.class))
                .as("첫 실행이 끝난 뒤에도 락이 남아 있다").isTrue();
    }

    @Test
    @DisplayName("스케줄러를 거치지 않은 직접 호출은 잠기지 않는다 — 시험·운영자 수동 실행용")
    void directCallIsNotLocked() {
        int before = job.runs.get();
        job.run();
        job.run();
        assertThat(job.runs.get() - before).isEqualTo(2);
    }
}
