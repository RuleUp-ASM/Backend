package com.ruleup.ruleup_backend.config;

import com.ruleup.ruleup_backend.RuleupBackendApplication;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

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

    /**
     * 메인 클래스 디렉터리의 .class 를 직접 순회한다.
     *
     * <p>Spring 의 ClassPathScanningCandidateComponentProvider 는 쓰지 않는다 — 클래스 메타데이터를
     * 읽다 실패하면 <b>예외 없이 그 클래스를 건너뛴다</b>. 실제로 CI(Linux)에서만 SystemMetricsSampler 가
     * 빠져 이 시험이 흔들렸다. 락이 빠진 배치를 잡아야 하는 시험이 조용히 클래스를 놓치면 안 된다.
     */
    private static List<Class<?>> scanMainClasses() throws Exception {
        Path root = Path.of(RuleupBackendApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<Class<?>> types = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                String name = root.relativize(f).toString()
                        .replace(java.io.File.separatorChar, '.')
                        .replaceAll("\\.class$", "");
                if (name.equals("module-info") || name.endsWith("package-info")) continue;
                types.add(Class.forName(name, false, SchedulerLockCoverageTest.class.getClassLoader()));
            }
        }
        assertThat(types).as("스캔이 비면 이 시험은 아무것도 지키지 못한다").hasSizeGreaterThan(100);
        assertThat(types).as("태스크별 목록의 클래스가 스캔에 잡혀야 한다")
                .extracting(Class::getSimpleName).contains("SystemMetricsSampler", "OutboxMetrics", "WatcherHealth");
        return types;
    }
}
