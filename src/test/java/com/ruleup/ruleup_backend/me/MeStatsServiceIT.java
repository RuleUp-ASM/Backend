package com.ruleup.ruleup_backend.me;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.me.dto.MeStatsResponse;
import com.ruleup.ruleup_backend.me.service.MeStatsService;
import com.ruleup.ruleup_backend.recommendation.domain.RoutineOutcome;
import com.ruleup.ruleup_backend.recommendation.repository.RoutineOutcomeRepository;
import com.ruleup.ruleup_backend.reputation.ReputationSnapshotRepository;
import com.ruleup.ruleup_backend.reputation.domain.ReputationSnapshot;
import com.ruleup.ruleup_backend.verification.domain.VerifiedVia;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.OAuthProvider;
import com.ruleup.ruleup_backend.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class MeStatsServiceIT {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired MeStatsService statsService;
    @Autowired RoutineOutcomeRepository outcomeRepo;
    @Autowired ReputationSnapshotRepository snapshotRepo;
    @Autowired UserRepository userRepository;

    private User newUser() {
        String u = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.saveAndFlush(User.create(
                OAuthProvider.KAKAO, "sub-" + u, null, "u-" + u, null, List.of()));
    }

    private void outcome(UUID userId, UUID challengeId, LocalDate date, VerificationStatus status) {
        outcomeRepo.saveAndFlush(RoutineOutcome.record(userId, challengeId, UUID.randomUUID(), null,
                "EXERCISE", date, status, status == VerificationStatus.SUCCESS ? VerifiedVia.AUTO : null,
                status == VerificationStatus.SUCCESS ? null : "NO_SIGNAL_RECEIVED", Instant.now()));
    }

    @Test
    @DisplayName("월간 통계: 완주율/완료수/온도변화/버킷/인사이트")
    void monthly() {
        User u = newUser();
        UUID c1 = UUID.randomUUID(), c2 = UUID.randomUUID();
        YearMonth lm = YearMonth.from(LocalDate.now(KST).minusMonths(1));
        LocalDate w1 = lm.atDay(3), w2 = lm.atDay(10);

        outcome(u.getId(), c1, w1, VerificationStatus.SUCCESS);
        outcome(u.getId(), c2, w1, VerificationStatus.SUCCESS);   // W1: 2/2
        outcome(u.getId(), c1, w2, VerificationStatus.SUCCESS);
        outcome(u.getId(), c2, w2, VerificationStatus.FAILED);    // W2: 1/2

        snapshotRepo.saveAndFlush(ReputationSnapshot.of(u.getId(), w1, new BigDecimal("40.00"), new BigDecimal("0.50"), "자격일 유지"));
        snapshotRepo.saveAndFlush(ReputationSnapshot.of(u.getId(), w2, new BigDecimal("39.80"), new BigDecimal("-0.20"), "페이스 하락"));

        MeStatsResponse res = statsService.stats(u.getId(), "MONTHLY", lm.atDay(15).toString());

        assertThat(res.period()).isEqualTo("MONTHLY");
        assertThat(res.totalCompleted()).isEqualTo(3);
        assertThat(res.avgCompletionRate()).isEqualTo(75);       // 3/(3+1)
        assertThat(res.mannerDelta()).isEqualByComparingTo("0.30");  // 0.50 - 0.20
        assertThat(res.series()).anySatisfy(s -> { if (s.bucket().equals("W1")) assertThat(s.completionRate()).isEqualTo(100); });
        assertThat(res.series()).anySatisfy(s -> { if (s.bucket().equals("W2")) assertThat(s.completionRate()).isEqualTo(50); });
        assertThat(res.insight()).isNotNull();
    }

    @Test
    @DisplayName("잘못된 기간은 INVALID_STATS_PERIOD")
    void invalidPeriod() {
        User u = newUser();
        assertThatThrownBy(() -> statsService.stats(u.getId(), "DAILY", null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_STATS_PERIOD);
    }

    @Test
    @DisplayName("데이터 없으면 0/빈 시리즈")
    void empty() {
        User u = newUser();
        MeStatsResponse res = statsService.stats(u.getId(), "WEEKLY", null);
        assertThat(res.totalCompleted()).isZero();
        assertThat(res.avgCompletionRate()).isZero();
        assertThat(res.mannerDelta()).isEqualByComparingTo("0.00");
        assertThat(res.series()).hasSize(7);   // 주간=일별 7점
        assertThat(res.insight()).isNull();
    }
}
