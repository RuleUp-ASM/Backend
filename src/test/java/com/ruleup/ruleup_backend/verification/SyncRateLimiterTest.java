package com.ruleup.ruleup_backend.verification;

import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.verification.config.VerificationProperties;
import com.ruleup.ruleup_backend.verification.service.SyncRateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 실패한 sync 는 간격 슬롯을 쓰지 않는다 — 곧바로 다시 누른 동기화가 429 로 튕기면 안 된다. */
class SyncRateLimiterTest {

    private final VerificationProperties properties = mock(VerificationProperties.class);
    private final SyncRateLimiter limiter;

    SyncRateLimiterTest() {
        when(properties.syncMinIntervalSec()).thenReturn(300);
        limiter = new SyncRateLimiter(properties);
    }

    @AfterEach
    void clear() {
        if (TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    @DisplayName("커밋된 sync 뒤 300초 안의 재요청은 429")
    void committedHoldsSlot() {
        TransactionSynchronizationManager.initSynchronization();
        limiter.check("u1", false);
        complete(TransactionSynchronization.STATUS_COMMITTED);

        assertThatThrownBy(() -> limiter.check("u1", false)).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("롤백된 sync 는 슬롯을 돌려준다")
    void rolledBackReleasesSlot() {
        TransactionSynchronizationManager.initSynchronization();
        limiter.check("u2", false);
        complete(TransactionSynchronization.STATUS_ROLLED_BACK);

        TransactionSynchronizationManager.initSynchronization();
        assertThatCode(() -> limiter.check("u2", false)).doesNotThrowAnyException();
    }

    private static void complete(int status) {
        var syncs = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();
        syncs.forEach(s -> s.afterCompletion(status));
    }
}
