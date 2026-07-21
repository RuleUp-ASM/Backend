package com.ruleup.ruleup_backend.me;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.domain.*;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.me.dto.CalendarDayResponse;
import com.ruleup.ruleup_backend.me.dto.CalendarMonthResponse;
import com.ruleup.ruleup_backend.me.service.MeCalendarService;
import com.ruleup.ruleup_backend.recommendation.domain.RoutineOutcome;
import com.ruleup.ruleup_backend.recommendation.repository.RoutineOutcomeRepository;
import com.ruleup.ruleup_backend.routine.domain.VerificationConfig;
import com.ruleup.ruleup_backend.verification.domain.VerifiedVia;
import com.ruleup.ruleup_backend.verification.domain.VerificationDaily;
import com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class MeCalendarServiceIT {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired MeCalendarService calendarService;
    @Autowired RoutineOutcomeRepository outcomeRepo;
    @Autowired VerificationDailyRepository dailyRepo;
    @Autowired ChallengeRepository challengeRepository;
    @Autowired com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository memberRepository;
    @Autowired UserRepository userRepository;

    private User newUser() {
        String u = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.saveAndFlush(User.create(
                OAuthProvider.KAKAO, "sub-" + u, null, "u-" + u, null, List.of()));
    }

    private Challenge newChallenge(UUID ownerId, String title) {
        Challenge c = Challenge.create(ownerId, title, null, null, "EXERCISE", ParticipationType.SOLO, null, null,
                List.of("MON"), 14, LocalDate.now(), null, VerificationConfig.manual(null), new LinkedHashMap<>(),
                new PenaltyConfig(BigDecimal.ONE, null, false), new RewardConfig(BigDecimal.ONE),
                Anonymity.REAL, false);
        return challengeRepository.saveAndFlush(c);
    }

    private void outcome(UUID userId, Challenge c, LocalDate date, VerificationStatus status, String reason) {
        outcomeRepo.saveAndFlush(RoutineOutcome.record(userId, c.getId(), UUID.randomUUID(), null,
                c.getCategory(), date, status, status == VerificationStatus.SUCCESS ? VerifiedVia.AUTO : null,
                reason, Instant.now()));
    }

    @Test
    @DisplayName("월 캘린더: 과거일 outcome 집계로 ALL_DONE/PARTIAL/FAILED 판정")
    void monthAggregation() {
        User u = newUser();
        Challenge c1 = newChallenge(u.getId(), "A");
        Challenge c2 = newChallenge(u.getId(), "B");
        Challenge c3 = newChallenge(u.getId(), "C");
        // 지난달 안전한 과거 날짜 사용(오늘 이전 보장)
        YearMonth lastMonth = YearMonth.from(LocalDate.now(KST).minusMonths(1));
        LocalDate dAllDone = lastMonth.atDay(10);
        LocalDate dPartial = lastMonth.atDay(11);
        LocalDate dFailed = lastMonth.atDay(12);

        outcome(u.getId(), c1, dAllDone, VerificationStatus.SUCCESS, null);
        outcome(u.getId(), c2, dAllDone, VerificationStatus.SUCCESS, null);
        outcome(u.getId(), c1, dPartial, VerificationStatus.SUCCESS, null);
        outcome(u.getId(), c2, dPartial, VerificationStatus.FAILED, "NO_SIGNAL_RECEIVED");
        outcome(u.getId(), c3, dFailed, VerificationStatus.FAILED, "NO_SIGNAL_RECEIVED");

        CalendarMonthResponse res = calendarService.month(u.getId(), lastMonth.toString());

        assertThat(res.month()).isEqualTo(lastMonth.toString());
        assertThat(res.days()).anySatisfy(d -> {
            if (d.date().equals(dAllDone.toString())) {
                assertThat(d.status()).isEqualTo("ALL_DONE");
                assertThat(d.successCount()).isEqualTo(2);
                assertThat(d.targetCount()).isEqualTo(2);
            }
        });
        assertThat(res.days()).anySatisfy(d -> {
            if (d.date().equals(dPartial.toString())) assertThat(d.status()).isEqualTo("PARTIAL");
        });
        assertThat(res.days()).anySatisfy(d -> {
            if (d.date().equals(dFailed.toString())) assertThat(d.status()).isEqualTo("FAILED");
        });
    }

    @Test
    @DisplayName("당일은 VerificationDaily PENDING 이 있으면 그 날 PENDING")
    void todayPending() {
        User u = newUser();
        Challenge c = newChallenge(u.getId(), "오늘챌린지");
        ChallengeMember m = memberRepository.saveAndFlush(ChallengeMember.owner(c.getId(), u.getId()));
        LocalDate today = LocalDate.now(KST);
        VerificationDaily d = VerificationDaily.open(m.getId(), c.getId(), u.getId(), today);
        dailyRepo.saveAndFlush(d);   // status PENDING

        CalendarMonthResponse res = calendarService.month(u.getId(), YearMonth.from(today).toString());
        assertThat(res.days()).anySatisfy(day -> {
            if (day.date().equals(today.toString())) assertThat(day.status()).isEqualTo("PENDING");
        });
    }

    @Test
    @DisplayName("일자 상세: 과거일은 RoutineOutcome 로 챌린지별 결과")
    void dayDetail() {
        User u = newUser();
        Challenge c = newChallenge(u.getId(), "물 2L 마시기");
        LocalDate past = LocalDate.now(KST).minusDays(3);
        outcome(u.getId(), c, past, VerificationStatus.FAILED, "NO_SIGNAL_RECEIVED");

        CalendarDayResponse res = calendarService.day(u.getId(), past.toString());
        assertThat(res.items()).hasSize(1);
        CalendarDayResponse.Item it = res.items().get(0);
        assertThat(it.title()).isEqualTo("물 2L 마시기");
        assertThat(it.status()).isEqualTo("FAILED");
        assertThat(it.failureReason()).isEqualTo("NO_SIGNAL_RECEIVED");
        assertThat(it.category()).isEqualTo("EXERCISE");
    }

    @Test
    @DisplayName("잘못된 월/날짜 형식은 에러")
    void invalidFormats() {
        User u = newUser();
        assertThatThrownBy(() -> calendarService.month(u.getId(), "2026/05"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_CALENDAR_MONTH);
        assertThatThrownBy(() -> calendarService.day(u.getId(), "20260501"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_CALENDAR_DATE);
    }
}
