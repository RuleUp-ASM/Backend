package com.ruleup.ruleup_backend.verification;

import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.verification.domain.VerificationDaily;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이의 제기 창 정책 가드 (2026-08-10 확정 — 기준: 챌린지 생성 및 운영 정책 §7.2).
 *
 *  - 신청 기한은 **실패 확정 후 1일**. 고정 시각(00시·03시) 앵커는 쓰지 않는다 —
 *    인증 신호로 판정이 가능해진 때 확정되고, 1일이 지나도록 신호가 없으면 실패로 처리한다.
 *  - 이의 기간 중에는 실패가 방 피드에 공유되면 안 된다(shareableAt = null) —
 *    인용될 수도 있는 실패로 망신을 주지 않기 위한 절대 조건.
 *  - 횟수 한도는 없다(구제권 개념 폐기) — 남용은 이상탐지가 담당하므로 여기서 셀 것이 없다.
 */
class AppealWindowPolicyTest {

    private VerificationDaily provisionalFailure(Instant confirmedAt) {
        VerificationDaily daily = VerificationDaily.open(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), LocalDate.now());
        daily.recordProvisionalFailure("SELF_CHECK", "NO_SIGNAL_RECEIVED",
                confirmedAt.plus(VerificationDaily.OBJECTION_WINDOW_DAYS, ChronoUnit.DAYS));
        return daily;
    }

    @Test
    @DisplayName("이의 제기 창은 1일이다 (구 3일·7일 표기 폐기)")
    void windowIsOneDay() {
        assertThat(VerificationDaily.OBJECTION_WINDOW_DAYS).isEqualTo(1);
    }

    @Test
    @DisplayName("잠정 실패는 확정 시각 +1일까지 이의 가능하고, 그동안 피드에 공유되지 않는다")
    void provisionalFailureOpensOneDayWindowAndStaysUnshared() {
        Instant confirmedAt = Instant.parse("2026-08-10T14:23:00Z");   // 고정 시각 아님 — 판정 가능해진 시점
        VerificationDaily daily = provisionalFailure(confirmedAt);

        assertThat(daily.getStatus()).isEqualTo(VerificationStatus.FAILED_PROVISIONAL);
        assertThat(daily.getDisputeClosesAt()).isEqualTo(confirmedAt.plus(1, ChronoUnit.DAYS));
        assertThat(daily.getShareableAt()).isNull();
    }

    @Test
    @DisplayName("이의 기간이 끝나 실패가 확정되면 그때부터 피드에 공유된다")
    void lockedFailureBecomesShareable() {
        Instant confirmedAt = Instant.parse("2026-08-10T14:23:00Z");
        VerificationDaily daily = provisionalFailure(confirmedAt);

        Instant lockedAt = confirmedAt.plus(1, ChronoUnit.DAYS);
        daily.lockFailed(lockedAt);

        assertThat(daily.getStatus()).isEqualTo(VerificationStatus.FAILED);
        assertThat(daily.getShareableAt()).isEqualTo(lockedAt);
    }

    @Test
    @DisplayName("이의가 인용되면 성공으로 정정된다 — 실패로는 공유되지 않는다")
    void acceptedAppealRestoresSuccess() {
        Instant confirmedAt = Instant.parse("2026-08-10T14:23:00Z");
        VerificationDaily daily = provisionalFailure(confirmedAt);

        Instant acceptedAt = confirmedAt.plus(3, ChronoUnit.HOURS);
        daily.approveObjection(acceptedAt);

        assertThat(daily.getStatus()).isEqualTo(VerificationStatus.SUCCESS);
        assertThat(daily.getFailureReason()).isNull();
    }
}
