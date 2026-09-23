package com.ruleup.ruleup_backend.config;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 모든 {@code @Scheduled} 가 「한 태스크만」 또는 「태스크마다」 중 하나로 분류돼 있는지 강제한다.
 *
 * <p>락 없이 새 배치를 추가하면 운영(태스크 여럿)에서 조용히 중복 실행된다 — 로컬·stg 는 태스크가
 * 하나라 드러나지 않는다. 태스크마다 도는 게 맞는 배치라면 아래 목록에 이유와 함께 올린다.
 */
class SchedulerLockCoverageTest {

    /** 태스크마다 돌아야 하는 배치(락 없음). 이유는 SchedulerLockConfig 주석. */
    private static final Set<String> PER_TASK = Set.of(
            "UserModerationQueue.poll",
            "ChallengeModerationQueue.poll",
            "SystemMetricsSampler.sample",
            "OutboxMetrics.refresh",
            "WatcherHealth.sample",
            "SegmentScoreService.evictLocalCaches");

    @Test
    @DisplayName("@Scheduled 는 전부 @SchedulerLock 이 있거나 태스크별 목록에 있다 — 락 이름은 겹치지 않는다")
    void everyScheduledMethodIsClassified() throws Exception {
        List<String> unclassified = new ArrayList<>();
        List<String> stalePerTask = new ArrayList<>(PER_TASK);
        Map<String, String> lockNames = new HashMap<>();
        List<String> duplicates = new ArrayList<>();

        for (Class<?> type : scanMainClasses()) {
            for (Method m : type.getDeclaredMethods()) {
                if (!m.isAnnotationPresent(Scheduled.class)) continue;
                String id = type.getSimpleName() + "." + m.getName();
                SchedulerLock lock = m.getAnnotation(SchedulerLock.class);
                if (PER_TASK.contains(id)) {
                    stalePerTask.remove(id);
                    assertThat(lock).as("%s 는 태스크별 목록에 있는데 락도 달려 있다", id).isNull();
                    continue;
                }
                if (lock == null) { unclassified.add(id); continue; }
                String prev = lockNames.put(lock.name(), id);
                if (prev != null) duplicates.add(lock.name() + " ← " + prev + ", " + id);
                assertThat(lock.name().length()).as("shedlock.name 은 64자").isLessThanOrEqualTo(64);
            }
        }

        assertThat(unclassified).as("락도 없고 태스크별 목록에도 없는 배치").isEmpty();
        assertThat(duplicates).as("락 이름이 겹치면 서로 다른 배치가 서로를 막는다").isEmpty();
        assertThat(stalePerTask).as("목록에 있지만 더는 없는 배치").isEmpty();
    }

    private static List<Class<?>> scanMainClasses() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter((reader, factory) -> true);
        List<Class<?>> types = new ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("com.ruleup.ruleup_backend")) {
            String name = bd.getBeanClassName();
            if (name == null || name.endsWith("Test") || name.endsWith("IT")) continue;
            types.add(Class.forName(name));
        }
        assertThat(types).as("스캔이 비면 이 시험은 아무것도 지키지 못한다").hasSizeGreaterThan(100);
        return types;
    }
}
