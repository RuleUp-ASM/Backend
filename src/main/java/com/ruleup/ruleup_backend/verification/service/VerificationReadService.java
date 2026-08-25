package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.service.ChallengeQueryService;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.verification.domain.VerificationConfig;
import com.ruleup.ruleup_backend.verification.domain.VerificationDaily;
import com.ruleup.ruleup_backend.verification.dto.ChallengeProgress;
import com.ruleup.ruleup_backend.verification.dto.TodayVerificationResponse;
import com.ruleup.ruleup_backend.verification.repository.ObjectionRepository;
import com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 인증 읽기 API — 진행률 일괄 조회와 "오늘 인증 결과" 조회.
 *
 * <p>오늘 인증 결과는 챌린지 상세의 "오늘 인증" 카드 + 판정 결과 모달을 함께 채운다.
 * 미확인 판정({@code unacknowledgedResult})이 실려 있으면 클라가 모달을 띄우고 ack 를 호출한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VerificationReadService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter ISO_OFFSET = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    /** CHECKING 인 이유 — 창은 닫혔는데 판정에 쓸 신호가 아직 다 도착하지 않았다. */
    private static final String WAITING_SIGNAL = "WAITING_SIGNAL";

    private final ChallengeQueryService challengeQuery;
    private final VerificationDailyRepository dailyRepo;
    private final ObjectionRepository objectionRepo;
    private final VerificationConfigFactory configFactory;
    private final StreakService streakService;

    // ===== GET /api/v1/verifications/progress — 진행률 일괄 =====
    public List<ChallengeProgress> progress(UUID userId, String statusFilter) {
        List<ChallengeMember> members = "ALL".equalsIgnoreCase(statusFilter)
                ? challengeQuery.findAllMemberships(userId)
                : challengeQuery.findActiveMemberships(userId);
        LocalDate today = LocalDate.now(KST);
        List<ChallengeProgress> out = new ArrayList<>();
        for (ChallengeMember m : members) {
            Challenge ch = challengeQuery.findActiveChallenge(m.getChallengeId()).orElse(null);
            if (ch == null) continue;
            out.add(toProgress(m, ch, configFactory.build(ch), today));
        }
        return out;
    }

    // ===== GET /api/v1/challenges/{challengeId}/verifications/today =====
    public TodayVerificationResponse today(UUID userId, UUID challengeId) {
        Challenge ch = challengeQuery.findActiveChallenge(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
        ChallengeMember member = challengeQuery.findMembership(challengeId, userId).orElse(null);
        if (member == null || !member.isActive()) {
            throw new BusinessException(ErrorCode.NOT_CHALLENGE_MEMBER);
        }

        VerificationConfig config = configFactory.build(ch);
        LocalDate today = LocalDate.now(KST);
        Instant now = Instant.now();
        VerificationDaily daily = dailyRepo
                .findByChallengeMemberIdAndTargetDate(member.getId(), today).orElse(null);

        boolean isTarget = isTodayTarget(config, ch, member, today);
        String status = todayStatus(isTarget, daily, today, now);
        boolean failed = TodayStatusView.FAILED.equals(status);

        return new TodayVerificationResponse(
                today.toString(),
                status,
                TodayStatusView.NOT_TARGET.equals(status) ? null : windowLabel(config),
                TodayStatusView.CHECKING.equals(status) ? WAITING_SIGNAL : null,
                (daily != null) ? formatKst(daily.getVerifiedAt()) : null,
                failed && daily != null ? daily.getFailureReason() : null,
                streakService.around(member.getId(), today),
                unacknowledged(daily),
                failed ? appeal(member, daily, now) : null);
    }

    // ===== 조립 헬퍼 =====

    /** 오늘이 대상 날짜가 아니면 판정 행과 무관하게 NOT_TARGET. 나머지는 공용 매핑(TodayStatusView). */
    private String todayStatus(boolean isTarget, VerificationDaily daily, LocalDate today, Instant now) {
        if (!isTarget) return TodayStatusView.NOT_TARGET;
        if (daily == null) return TodayStatusView.IN_PROGRESS;
        return TodayStatusView.of(daily.getStatus(), today, daily.getFailureReason(), now);
    }

    /** 인증 창 표시 문구 — 자동은 시간대, 수동은 "자정 마감". 시간 제약이 없으면 null. */
    private String windowLabel(VerificationConfig config) {
        if (config.isManual()) return "자정 마감";
        if (config.primaryMethod() == null) return null;
        return switch (config.primaryMethod()) {
            case GPS_PRESENCE -> (config.gps() != null) ? config.gps().timeWindow() : null;
            case SCREEN_TIME -> (config.screenTime() != null) ? config.screenTime().timeWindow() : null;
            case WAKE -> (config.wake() != null && config.wake().beforeTime() != null)
                    ? "~" + config.wake().beforeTime() : null;
            case SLEEP -> (config.sleep() != null && config.sleep().bedtimeBefore() != null)
                    ? "~" + config.sleep().bedtimeBefore() : null;
            default -> null;
        };
    }

    /** 아직 확인하지 않은 종결 판정 — 클라는 이게 있으면 결과 모달을 띄우고 ack 를 호출한다. */
    private TodayVerificationResponse.UnacknowledgedResult unacknowledged(VerificationDaily daily) {
        if (daily == null || !daily.hasUnacknowledgedResult()) return null;
        return new TodayVerificationResponse.UnacknowledgedResult(
                daily.getId().toString(),
                daily.getStatus() == VerificationStatus.SUCCESS ? "DONE" : "FAILED");
    }

    /**
     * 이의 신청 가능 여부와 기한. 기한은 확정 시각 +24시간이 아니라
     * <b>실패 확정일의 다음 날 00:00 KST</b>로 고정된 자정 경계다(인증 정책 §5.2).
     * 횟수 한도는 없고, 솔로·그룹을 가리지 않는다 — 자동 판정이 틀리는 건 어느 쪽에서나 같다.
     */
    private TodayVerificationResponse.Appeal appeal(ChallengeMember member, VerificationDaily daily, Instant now) {
        if (daily == null || daily.getAppealClosesAt() == null) return null;
        boolean alreadyFiled = objectionRepo
                .findByChallengeMemberIdAndTargetDate(member.getId(), daily.getTargetDate()).isPresent();
        return new TodayVerificationResponse.Appeal(
                ZonedDateTime.ofInstant(daily.getAppealClosesAt(), KST).format(ISO_OFFSET),
                !alreadyFiled && daily.isAppealable(now));
    }

    private ChallengeProgress toProgress(ChallengeMember m, Challenge ch, VerificationConfig config, LocalDate today) {
        boolean freq = config.isFrequency();
        int remaining = freq
                ? Math.max(m.getTargetDays() - m.getSuccessDays(), 0)
                : Math.max(m.getTargetDays() - m.getSuccessDays() - m.getFailDays(), 0);
        return new ChallengeProgress(
                ch.getId().toString(), ch.getTitle(), ch.getCategory(), ch.getParticipationType().name(),
                ch.getStatus().name(), m.getScheduleType().name(), m.getProgressRate(),
                m.getSuccessDays(), m.getTargetDays(), remaining,
                isTodayTarget(config, ch, m, today),
                m.getTodayStatus() != null ? m.getTodayStatus().name() : null,
                m.getSetupStatus() != null ? m.getSetupStatus().name() : null,
                freq ? toPeriod(m) : null,
                (m.getLastSyncedAt() != null) ? m.getLastSyncedAt().toString() : null);
    }

    private boolean isTodayTarget(VerificationConfig config, Challenge ch, ChallengeMember m, LocalDate today) {
        if (config.isFrequency()) {
            Integer done = m.getCurPeriodCompleted(), need = m.getPeriodTarget();
            return !(done != null && need != null && done >= need);
        }
        List<String> repeat = ch.getRepeatDays();
        return repeat != null && repeat.contains(WeekdayCodes.code(today.getDayOfWeek()));
    }

    private ChallengeProgress.Period toPeriod(ChallengeMember m) {
        Integer target = m.getPeriodTarget(), completed = m.getCurPeriodCompleted();
        Integer remaining = (target != null && completed != null) ? Math.max(target - completed, 0) : null;
        return new ChallengeProgress.Period(
                m.getPeriodUnit() != null ? m.getPeriodUnit().name() : null,
                target, completed, remaining,
                m.getCurPeriodEnd() != null ? m.getCurPeriodEnd().toString() : null);
    }

    private String formatKst(Instant instant) {
        return (instant != null) ? ZonedDateTime.ofInstant(instant, KST).format(ISO_OFFSET) : null;
    }
}
