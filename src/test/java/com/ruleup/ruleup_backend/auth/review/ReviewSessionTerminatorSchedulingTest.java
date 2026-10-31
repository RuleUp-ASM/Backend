package com.ruleup.ruleup_backend.auth.review;

import com.ruleup.ruleup_backend.auth.repository.RefreshTokenRepository;
import com.ruleup.ruleup_backend.user.UserAccountService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;

/**
 * 종료 스케줄러가 <b>진짜 스케줄러 위에서</b> 주기마다·기동 직후 도는가 (심사 계정 테크 스펙 5-7 · 오픈 이슈 #7).
 *
 * <p>통합 테스트 컨텍스트는 스케줄링을 끈다({@code app.scheduling.enabled=false}) — 그래서 ReviewLoginIT 는
 * 메서드를 직접 부르고, 어노테이션 값만으로는 「실제로 돈다」를 보장할 수 없다. 여기서는 작은 컨텍스트에
 * {@code @EnableScheduling} 을 켜고 실제 실행을 센다.
 *
 * <ul>
 *   <li>주기는 설정값 {@code app.review-login.terminator-interval} 을 따른다 — 짧게 주면 짧게 돈다</li>
 *   <li>첫 실행은 기동 직후다 — 주기를 1시간으로 둬도 곧바로 한 번 돈다. 스위치를 끄고 강제 재배포하면
 *       새 태스크가 다음 주기를 기다리지 않고 바로 RT 를 정리한다</li>
 * </ul>
 *
 * <p>끊김 보장(꺼진 시각 + AT 수명)은 이 스케줄러에 기대지 않는다 — 재발급 차단이 꺼지는 순간부터 걸린다
 * (ReviewSessionCutoffIT). 스케줄러는 회수된 RT 가 스위치를 다시 켰을 때 살아나지 않게 정리하는 역할이다.
 *
 * <p>스위치는 켜진 상태로 두고 폐기된 코드도 없다(목의 기본값 — 빈 목록) — 회수할 것이 없어 저장소 목은 거의 쓰이지 않는다.
 * 의존성은 정책({@link ReviewAccountPolicy}) · RT 저장소 · 탈퇴 서비스(UserAccountService — 꺼지면 codes 계정을 일반 탈퇴와
 * 같은 흐름으로 탈퇴시킨다)다. 심사 계정은 푸시 토큰을 저장하지 않으므로 푸시는 내릴 것이 없다.
 */
class ReviewSessionTerminatorSchedulingTest {

    @Configuration
    @EnableScheduling
    static class SchedulingOn {}

    private static ApplicationContextRunner runner(ReviewAccountPolicy policy, String interval) {
        return new ApplicationContextRunner()
                .withUserConfiguration(SchedulingOn.class)
                .withBean(ReviewAccountPolicy.class, () -> policy)
                .withBean(RefreshTokenRepository.class, () -> mock(RefreshTokenRepository.class))
                .withBean(UserAccountService.class, () -> mock(UserAccountService.class))
                .withBean(ReviewSessionTerminator.class)
                .withPropertyValues("app.review-login.terminator-interval=" + interval);
    }

    /**
     * 스위치가 켜진 정책 목. 테스트 스레드가 아닌 스레드(스케줄러)에서 정책을 읽은 횟수를 센다 — 매 실행은 정책(스위치·
     * 종료 대상)을 적어도 한 번 읽는다. 어느 메서드인지는 묻지 않는다. 생성자·테스트 스레드의 호출은 세지 않는다.
     */
    private static final class CountingPolicy {
        final AtomicInteger scheduledReads = new AtomicInteger();
        final ReviewAccountPolicy mock;

        CountingPolicy() {
            Thread testThread = Thread.currentThread();
            mock = mock(ReviewAccountPolicy.class, inv -> {
                if (Thread.currentThread() != testThread) scheduledReads.incrementAndGet();
                if (inv.getMethod().getName().equals("isSwitchOn")) return true;
                return RETURNS_DEFAULTS.answer(inv);
            });
        }

        void awaitReads(int atLeast) throws InterruptedException {
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (scheduledReads.get() < atLeast && System.nanoTime() < deadline) Thread.sleep(20);
            assertThat(scheduledReads.get()).as("3초 안에 스케줄러 스레드가 정책을 읽은 횟수").isGreaterThanOrEqualTo(atLeast);
        }
    }

    @Test
    @DisplayName("설정한 주기마다 실제로 돈다")
    void runsRepeatedlyAtConfiguredInterval() {
        CountingPolicy policy = new CountingPolicy();
        // 실행 하나가 정책을 여러 번 읽을 수 있으므로 넉넉히 센다 — 0.05초 주기면 3초에 수십 번 돈다.
        runner(policy.mock, "PT0.05S").run(ctx -> policy.awaitReads(30));
    }

    @Test
    @DisplayName("기동 직후 한 번 돈다 — 주기가 길어도 첫 실행을 기다리지 않는다")
    void runsRightAfterStartup() {
        CountingPolicy policy = new CountingPolicy();

        runner(policy.mock, "PT1H").run(ctx -> policy.awaitReads(1));
    }
}
