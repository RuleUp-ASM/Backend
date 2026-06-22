package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeStatus;
import com.ruleup.ruleup_backend.challenge.domain.MemberStatus;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.verification.domain.*;
import com.ruleup.ruleup_backend.verification.dto.ChallengeProgress;
import com.ruleup.ruleup_backend.verification.dto.VerificationDetailResponse;
import com.ruleup.ruleup_backend.verification.dto.VerificationDetailResponse.*;
import com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository;
import com.ruleup.ruleup_backend.verification.repository.VerificationMethodResultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 인증 읽기 API (§3.2 진행률 일괄 / §3.3 상세 검증결과). 비정규화 카운터 + config 파생으로 조립. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VerificationReadService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final ChallengeMemberRepository memberRepo;
    private final ChallengeRepository challengeRepo;
    private final VerificationDailyRepository dailyRepo;
    private final VerificationMethodResultRepository methodResultRepo;
    private final VerificationConfigFactory configFactory;

    // ===== §3.2 진행률 일괄 =====
    public List<ChallengeProgress> progress(UUID userId, String statusFilter) {
        List<ChallengeMember> members = "ALL".equalsIgnoreCase(statusFilter)
                ? memberRepo.findByUserId(userId)
                : memberRepo.findByUserIdAndStatus(userId, MemberStatus.ACTIVE);
        LocalDate today = LocalDate.now(KST);
        List<ChallengeProgress> out = new ArrayList<>();
        for (ChallengeMember m : members) {
            Challenge ch = challengeRepo.findByIdAndDeletedAtIsNull(m.getChallengeId()).orElse(null);
            if (ch == null) continue;
            out.add(toProgress(m, ch, configFactory.build(ch), today));
        }
        return out;
    }

    // ===== §3.3 상세 검증 결과 =====
    public VerificationDetailResponse detail(UUID userId, UUID challengeId, int logDays) {
        Challenge ch = challengeRepo.findByIdAndDeletedAtIsNull(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
        ChallengeMember member = memberRepo.findByChallengeIdAndUserId(challengeId, userId).orElse(null);
        if (member == null || !member.isActive()) {
            throw new BusinessException(ErrorCode.NOT_CHALLENGE_MEMBER);
        }
        VerificationConfig config = configFactory.build(ch);
        LocalDate today = LocalDate.now(KST);

        VerificationDaily todayDaily = dailyRepo.findByChallengeMemberIdAndTargetDate(member.getId(), today).orElse(null);
        List<VerificationMethodResult> todayResults = (todayDaily != null)
                ? methodResultRepo.findByVerificationDailyId(todayDaily.getId()) : List.of();

        Today todayDto = buildToday(member, ch, config, today, todayDaily, todayResults);
        List<MethodEval> methods = todayResults.stream()
                .map(mr -> new MethodEval(mr.getMethod(),
                        str(mr.getLastEvaluatedAt()), mr.isSupported(),
                        mr.getPolarity() != null ? mr.getPolarity().name() : null, mr.getEvidence()))
                .toList();

        List<DailyLog> logs = dailyRepo.findByChallengeMemberIdOrderByTargetDateDesc(member.getId()).stream()
                .limit(Math.max(logDays, 1))
                .map(d -> new DailyLog(d.getTargetDate().toString(), d.getStatus().name(),
                        d.getMethod(), str(d.getVerifiedAt())))
                .toList();

        boolean freq = config.isFrequency();
        int remaining = freq
                ? Math.max(member.getTargetDays() - member.getSuccessDays(), 0)
                : Math.max(member.getTargetDays() - member.getSuccessDays() - member.getFailDays(), 0);

        Verification v = new Verification(
                overallStatus(member, ch), member.getScheduleType().name(),
                member.getProgressRate(), member.getSuccessDays(), member.getTargetDays(), remaining,
                freq ? toPeriod(member) : null, todayDto, methods, logs);

        return new VerificationDetailResponse(ch.getId().toString(), ch.getTitle(), ch.getStatus().name(), v);
    }

    // ===== 조립 헬퍼 =====
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
                freq ? toPeriod(m) : null,
                str(m.getLastSyncedAt()));
    }

    private Today buildToday(ChallengeMember m, Challenge ch, VerificationConfig config, LocalDate today,
                             VerificationDaily daily, List<VerificationMethodResult> results) {
        boolean isTarget = isTodayTarget(config, ch, m, today);
        if (daily == null) {
            return new Today(isTarget, isTarget ? "PENDING" : "NOT_TARGET", null, null, null, null);
        }
        var evidence = results.isEmpty() ? null : results.get(0).getEvidence();
        return new Today(isTarget, daily.getStatus().name(),
                str(daily.getWindowClosesAt()), str(daily.getVerifiedAt()),
                daily.getFailureReason(), evidence);
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

    private String overallStatus(ChallengeMember m, Challenge ch) {
        BigDecimal rate = m.getProgressRate();
        if (ch.getStatus() == ChallengeStatus.ENDED) {
            return (rate != null && rate.compareTo(BigDecimal.valueOf(100)) >= 0) ? "COMPLETED" : "FAILED";
        }
        if (m.getFailDays() > 0 || m.getTodayStatus() == VerificationStatus.FAILED) return "AT_RISK";
        return "ON_TRACK";
    }

    private String str(java.time.Instant t) { return (t != null) ? t.toString() : null; }
}
