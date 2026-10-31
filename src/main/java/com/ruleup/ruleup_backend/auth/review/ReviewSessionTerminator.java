package com.ruleup.ruleup_backend.auth.review;

import com.ruleup.ruleup_backend.auth.repository.RefreshTokenRepository;
import com.ruleup.ruleup_backend.user.UserAccountService;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 심사 세션 종료 스케줄러 (심사 계정 테크 스펙 5-1 · 5-7 · 5-8).
 *
 * <ul>
 *   <li><b>스위치가 꺼지면</b> codes 계정(폐기 포함)을 <b>일반 탈퇴와 같은 흐름</b>({@link UserAccountService#withdraw})으로
 *       탈퇴시킨다 — RT 회수·방 나가기·봇 방장 전환까지 같다. 다음 심사 때 같은 회차 안이면 코드로 복원된다</li>
 *   <li><b>켜져 있으면</b> 폐기된 코드 계정의 RT 만 회수한다(유출 대응). 탈퇴시키지 않는다</li>
 * </ul>
 *
 * <p>끊김 보장(꺼진 시각 + AT 수명)은 이 스케줄러에 기대지 않는다 — 재발급 차단이 꺼지는 순간부터 걸린다.
 * 이 스케줄러는 회수된 RT 가 스위치를 다시 켰을 때 살아나지 않게 정리하고 계정을 닫는다. 주기 1분은 확인 간격이지
 * 보장이 아니다.
 *
 * <h4>여러 태스크·부분 실패</h4>
 * ShedLock 을 쓰지 않는다 — 강제 재배포 직후 새 태스크의 「기동 직후 실행」이 앞 태스크의 락에 막히면 안 된다.
 * 대신 계정마다 트랜잭션 하나에서 행을 {@code FOR UPDATE} 로 잠그고 상태를 다시 본 뒤 탈퇴시키므로 겹쳐 돌아도
 * 계정마다 탈퇴는 한 번이다. 한 계정이 실패하면 그 계정의 쓰기는 모두 롤백되고 나머지는 계속하며, 다음 실행에서 다시 한다.
 *
 * <p>RT 저장소·탈퇴 서비스 외의 의존성은 {@link ObjectProvider} 로 받는다 — 스케줄링 계약 테스트가 작은 컨텍스트에서
 * 이 빈만 띄운다.
 */
@Component
public class ReviewSessionTerminator {

    private static final Logger log = LoggerFactory.getLogger(ReviewSessionTerminator.class);

    private final ReviewAccountPolicy policy;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserAccountService userAccountService;
    private final ObjectProvider<UserRepository> userRepository;
    private final ObjectProvider<PlatformTransactionManager> transactionManager;

    public ReviewSessionTerminator(ReviewAccountPolicy policy,
                                   RefreshTokenRepository refreshTokenRepository,
                                   UserAccountService userAccountService,
                                   ObjectProvider<UserRepository> userRepository,
                                   ObjectProvider<PlatformTransactionManager> transactionManager) {
        this.policy = policy;
        this.refreshTokenRepository = refreshTokenRepository;
        this.userAccountService = userAccountService;
        this.userRepository = userRepository;
        this.transactionManager = transactionManager;
    }

    /**
     * 한 번 돈다. 기동 직후 바로 한 번 돌고 그 뒤 주기마다 돈다.
     *
     * @return 처리한 수 — 탈퇴시킨 계정 수 + 회수한 RT 수. 다시 돌면 0 이다(멱등)
     */
    @Scheduled(fixedDelayString = "${app.review-login.terminator-interval:PT1M}", initialDelay = 0)
    public int revokeEndedSessions() {
        int done = 0;
        if (!policy.isSwitchOn()) {
            for (UUID id : policy.codeAccountIds()) done += closeAccount(id);
        } else {
            for (UUID id : policy.revokedCodeAccountIds()) done += revokeSessions(id);
        }
        if (done > 0) log.info("review_session_terminated count={}", done);
        return done;
    }

    /** 계정 행을 잠그고 다시 본 뒤 탈퇴시킨다. 이미 탈퇴했으면 남은 RT 만 정리한다. 실패하면 0 — 다음 실행에서 다시 한다. */
    private int closeAccount(UUID userId) {
        try {
            return inTransaction(() -> {
                User user = userRepository.getObject().findByIdForUpdate(userId).orElse(null);
                if (user == null) return 0;
                if (user.isWithdrawn()) return refreshTokenRepository.revokeAllByUserId(userId, Instant.now());
                userAccountService.withdraw(userId, UserAccountService.CONFIRM_PHRASE);
                return 1;
            });
        } catch (RuntimeException e) {
            log.warn("review_session_close_failed userId={} error={}", userId, e.toString());
            return 0;
        }
    }

    private int revokeSessions(UUID userId) {
        try {
            return inTransaction(() -> refreshTokenRepository.revokeAllByUserId(userId, Instant.now()));
        } catch (RuntimeException e) {
            log.warn("review_session_revoke_failed userId={} error={}", userId, e.toString());
            return 0;
        }
    }

    private int inTransaction(Supplier<Integer> work) {
        Integer n = new TransactionTemplate(transactionManager.getObject()).execute(status -> work.get());
        return (n != null) ? n : 0;
    }
}
