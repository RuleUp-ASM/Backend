package com.ruleup.ruleup_backend.sanction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 심사 계정 면제의 경계.
 *
 * <p>이 예외는 <b>새는 것</b>이 가장 위험하다. 그래서 「면제되지 않는다」를 먼저 못 박는다 —
 * 설정이 비었을 때, 목록에 없을 때, 값이 깨졌을 때는 전부 일반 계정이어야 한다.
 */
class ReviewAccountsTest {

    private static final UUID REVIEWER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID IOS_REVIEWER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID NORMAL_USER = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Test
    @DisplayName("설정이 비어 있으면 아무도 면제되지 않는다 — 기본값이라 평소 동작이 달라지지 않는다")
    void emptyConfigExemptsNobody() {
        assertThat(new ReviewAccounts("").isExempt(REVIEWER)).isFalse();
        assertThat(new ReviewAccounts(null).isExempt(REVIEWER)).isFalse();
        assertThat(new ReviewAccounts("   ").isExempt(REVIEWER)).isFalse();
    }

    @Test
    @DisplayName("목록에 있으면 면제한다")
    void listedAccountIsExempt() {
        assertThat(new ReviewAccounts(REVIEWER.toString()).isExempt(REVIEWER)).isTrue();
    }

    @Test
    @DisplayName("목록에 없는 계정은 면제되지 않는다 — 면제가 전체로 새지 않는다")
    void othersAreNotExempt() {
        assertThat(new ReviewAccounts(REVIEWER.toString()).isExempt(NORMAL_USER)).isFalse();
    }

    @Test
    @DisplayName("쉼표로 여러 계정을 받는다 — 안드로이드·iOS 심사 계정이 따로 생긴다")
    void acceptsSeveralIdsWithWhitespace() {
        var accounts = new ReviewAccounts("  " + REVIEWER + " ,  " + IOS_REVIEWER + "  ");
        assertThat(accounts.isExempt(REVIEWER)).isTrue();
        assertThat(accounts.isExempt(IOS_REVIEWER)).isTrue();
        assertThat(accounts.isExempt(NORMAL_USER)).isFalse();
    }

    @Test
    @DisplayName("읽을 수 없는 값은 기동을 막지 않고, 그 값만 무시한다")
    void malformedIdIsIgnoredWithoutFailingStartup() {
        var accounts = new ReviewAccounts("not-a-uuid," + REVIEWER);
        assertThat(accounts.isExempt(REVIEWER)).as("멀쩡한 값은 살아남는다").isTrue();
        assertThat(accounts.isExempt(NORMAL_USER)).isFalse();
    }

    @Test
    @DisplayName("사용자가 없으면 면제하지 않는다")
    void nullUserIsNotExempt() {
        assertThat(new ReviewAccounts(REVIEWER.toString()).isExempt(null)).isFalse();
    }
}
