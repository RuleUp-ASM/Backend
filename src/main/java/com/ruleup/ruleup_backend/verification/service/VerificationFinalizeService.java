package com.ruleup.ruleup_backend.verification.service;
import com.ruleup.ruleup_backend.common.verification.*;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.service.ChallengeQueryService;
import com.ruleup.ruleup_backend.challenge.stats.ChallengeStatsRefreshRequested;
import com.ruleup.ruleup_backend.verification.domain.*;
import com.ruleup.ruleup_backend.verification.repository.ObjectionRepository;
import com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository;
import com.ruleup.ruleup_backend.verification.repository.VerificationMethodResultRepository;
import com.ruleup.ruleup_backend.common.event.RoutineFailureConfirmed;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 인증 확정 배치(§2.11~2.14). 두 작업:
 *  1) finalizeDue   : 유예 끝난 PENDING 행을 polarity대로 잠금(도달형→FAILED / 제약형→SUCCESS).
 *                     FOR UPDATE SKIP LOCKED 선점이라 다중 인스턴스에서도 중복 확정 없음
 *                     (스케일 시 ShedLock을 위에 얹어도 공존 — DB 멱등은 그대로 유효).
 *  2) rolloverFrequencyPeriods : 빈도형 주기 종료분을 미달 정산 + 다음 주기로 롤오버(§2.12).
 */
@Service
@RequiredArgsConstructor
public class VerificationFinalizeService {

    private static final Logger log = LoggerFactory.getLogger(VerificationFinalizeService.class);

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int CLAIM_LIMIT = 200;

    private final VerificationDailyRepository dailyRepo;
    private final VerificationMethodResultRepository methodResultRepo;
    private final ObjectionRepository objectionRepo;
    private final ChallengeQueryService challengeQuery;
    private final VerificationConfigFactory configFactory;
    private final VerificationProgressService progressService;
    private final ApplicationEventPublisher eventPublisher;

    /** 1분마다: 유예 끝난 PENDING 확정. */
    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void finalizeDue() {
        Instant now = Instant.now();
        List<VerificationDaily> due = dailyRepo.findDuePendingForUpdate(now, CLAIM_LIMIT);
        Set<UUID> changedChallenges = new HashSet<>();
        for (VerificationDaily daily : due) {
            if (finalizeOne(daily, now)) changedChallenges.add(daily.getChallengeId());
        }
        changedChallenges.forEach(challengeId -> eventPublisher.publishEvent(
                ChallengeStatsRefreshRequested.of(challengeId, "VERIFICATION_FINALIZED")));
        if (!due.isEmpty()) {
            log.info("인증 확정 배치: 유예 끝난 PENDING {}건 확정 처리", due.size());
        }
    }

    private boolean finalizeOne(VerificationDaily daily, Instant now) {
        Challenge challenge = challengeQuery.findChallenge(daily.getChallengeId()).orElse(null);
        if (challenge == null) {
            daily.recordResult(VerificationStatus.FAILED, null, "NO_SIGNAL_RECEIVED", now);
            return false;
        }
        VerificationConfig config = configFactory.build(challenge);
        VerificationMethod method = config.primaryMethod();
        Polarity polarity = polarityOf(config, method);

        boolean finalDecision = false;
        boolean confirmedFail = false;
        if (polarity == Polarity.CONSTRAINT) {
            // 제약형(MAX·AVOID): 위반이면 sync에서 이미 잠정/확정 잠김 → 여기 온 건 무위반 → SUCCESS
            daily.recordResult(VerificationStatus.SUCCESS, method.name(), null, now);
            finalDecision = true;
        } else {
            // 도달형: 창 닫힘·미충족. 신호 자체가 없었으면 NO_SIGNAL_RECEIVED.
            var mr = methodResultRepo.findByVerificationDailyIdAndMethod(daily.getId(), method.name()).orElse(null);
            Object pendingReason = (mr != null && mr.getEvidence() != null) ? mr.getEvidence().get("pendingReason") : null;
            String reason;
            if (mr == null) {
                reason = "NO_SIGNAL_RECEIVED";
            } else if ("UNTRUSTED_HEALTH_SOURCE".equals(pendingReason)) {
                reason = "UNTRUSTED_HEALTH_SOURCE";   // HEALTH: 신뢰 게이트로만 막혀 통과분 0(§8.2)
            } else if ("PERMISSION_MISSING".equals(pendingReason)) {
                reason = "PERMISSION_MISSING";        // ③ 권한 공백(gaps)으로 신호 수집 불가(§8.5) — NO_SIGNAL과 구분
            } else {
                reason = failureReasonFor(method, config);
            }
            // 실패 2단계(§8.7): 그룹은 잠정 실패(1일 이의 제기 창) → 온도/통지는 최종 lock에서.
            //                 솔로는 이의 제기가 무의미 → 즉시 FAILED 확정.
            if (challenge.isGroup()) {
                daily.recordProvisionalFailure(method.name(), reason,
                        now.plus(VerificationDaily.OBJECTION_WINDOW_DAYS, ChronoUnit.DAYS));
            } else {
                daily.recordResult(VerificationStatus.FAILED, null, reason, now);
                confirmedFail = true;
                finalDecision = true;
            }
        }

        ChallengeMember member = challengeQuery.findMember(daily.getChallengeMemberId()).orElse(null);
        refreshProgress(member, daily);

        // 확정된 실패만 감시자 통지 적재(§9). 잠정 실패는 확정 아님 → lock 시점에 통지.
        if (confirmedFail && member != null) {
            eventPublisher.publishEvent(new RoutineFailureConfirmed(
                    daily.getChallengeId(), member.getUserId(), daily.getTargetDate(), now));
        }
        return finalDecision && member != null;
    }

    /**
     * 잠정 실패 확정 배치(§8.7): 이의 제기 창이 끝난 FAILED_PROVISIONAL 을 FAILED 로 잠근다(온도 반영).
     * 단, 미처리(PENDING) 이의 제기가 있으면 처리될 때까지 확정을 보류한다(자동 기각 아님).
     */
    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void lockExpiredProvisionalFailures() {
        Instant now = Instant.now();
        List<VerificationDaily> due = dailyRepo.findProvisionalDueForLockForUpdate(now, CLAIM_LIMIT);
        int locked = 0;
        Set<UUID> changedChallenges = new HashSet<>();
        for (VerificationDaily daily : due) {
            // 처리 대기 중인 이의 제기가 있으면 보류(결정 시점에 처리).
            if (objectionRepo.existsByChallengeMemberIdAndTargetDateAndStatus(
                    daily.getChallengeMemberId(), daily.getTargetDate(), ObjectionStatus.PENDING)) {
                continue;
            }
            daily.lockFailed(now);   // FAILED 확정(온도 반영 트리거)
            ChallengeMember member = challengeQuery.findMember(daily.getChallengeMemberId()).orElse(null);
            refreshProgress(member, daily);
            if (member != null) {
                changedChallenges.add(daily.getChallengeId());
                eventPublisher.publishEvent(new RoutineFailureConfirmed(
                        daily.getChallengeId(), member.getUserId(), daily.getTargetDate(), now));
            }
            locked++;
        }
        changedChallenges.forEach(challengeId -> eventPublisher.publishEvent(
                ChallengeStatsRefreshRequested.of(challengeId, "PROVISIONAL_FAILURE_LOCKED")));
        if (locked > 0) log.info("잠정 실패 확정 배치: {}건 FAILED lock", locked);
    }

    /** 진행률 재계산 + (그날이 오늘이면) todayStatus 뱃지 캐시 갱신. */
    private void refreshProgress(ChallengeMember member, VerificationDaily daily) {
        if (member == null) return;
        if (daily.getTargetDate().equals(LocalDate.now(KST))) {
            progressService.recountAndSetToday(member, daily.getStatus());
        } else {
            progressService.recount(member);
        }
    }

    // (제거) 예비 폴백 "침묵=동의" 자동확정 sweeper — 방장 승인 모델(§9.2)로 전환되어 더는 필요 없다.
    // 폴백 확정은 VerificationApprovalService(.../approval) 에서 방장 승인/거절로만 일어난다.

    /** 매일 00:05 KST: 종료된 빈도형 주기 정산 + 롤오버. */
    @Scheduled(cron = "0 5 0 * * *", zone = "Asia/Seoul")
    @Transactional
    public void rolloverFrequencyPeriods() {
        LocalDate today = LocalDate.now(KST);
        List<ChallengeMember> members = challengeQuery.findFrequencyRolloverTargets(today);
        Set<UUID> changedChallenges = new HashSet<>();
        for (ChallengeMember m : members) {
            Challenge ch = challengeQuery.findActiveChallenge(m.getChallengeId()).orElse(null);
            if (ch == null) continue;
            if (rolloverMember(m, ch, today)) {
                progressService.recount(m);
                changedChallenges.add(ch.getId());
            }
        }
        changedChallenges.forEach(challengeId -> eventPublisher.publishEvent(
                ChallengeStatsRefreshRequested.of(challengeId, "FREQUENCY_ROLLOVER")));
        if (!members.isEmpty()) {
            log.info("빈도형 주기 롤오버: 대상 {}건 정산", members.size());
        }
    }

    /** 종료된 주기를 따라잡으며 정산(다운타임으로 여러 주기가 밀렸어도 catch-up). */
    private boolean rolloverMember(ChallengeMember m, Challenge ch, LocalDate today) {
        int guard = 0;
        boolean changed = false;
        while (m.getCurPeriodEnd() != null && m.getCurPeriodEnd().isBefore(today) && guard++ < 400) {
            int need = (m.getPeriodTarget() != null) ? m.getPeriodTarget() : 0;
            int done = (m.getCurPeriodCompleted() != null) ? m.getCurPeriodCompleted() : 0;
            int shortfall = Math.max(need - done, 0);

            LocalDate nextStart = m.getCurPeriodEnd().plusDays(1);
            if (nextStart.isAfter(ch.getEndDate())) {
                // 챌린지 종료: 마지막 주기 미달만 정산하고 advance 안 함(루프 종료)
                m.rolloverPeriod(m.getCurPeriodStart(), m.getCurPeriodEnd(), shortfall);
                changed = true;
                break;
            }
            int periodDays = (m.getPeriodUnit() == PeriodUnit.WEEK) ? 7 : 30;
            LocalDate nextEnd = nextStart.plusDays(periodDays - 1L);
            if (nextEnd.isAfter(ch.getEndDate())) nextEnd = ch.getEndDate();
            m.rolloverPeriod(nextStart, nextEnd, shortfall);
            changed = true;
        }
        return changed;
    }

    private Polarity polarityOf(VerificationConfig c, VerificationMethod method) {
        return switch (method) {
            case SCREEN_TIME -> (c.screenTime() != null && c.screenTime().polarity() != null) ? c.screenTime().polarity() : Polarity.ACHIEVEMENT;
            case GPS_PRESENCE, GPS_DISTANCE -> (c.gps() != null && c.gps().polarity() != null) ? c.gps().polarity() : Polarity.ACHIEVEMENT;
            case HEALTH -> (c.health() != null && c.health().polarity() != null) ? c.health().polarity() : Polarity.ACHIEVEMENT;
            case SLEEP -> (c.sleep() != null && c.sleep().polarity() != null) ? c.sleep().polarity() : Polarity.ACHIEVEMENT;
            default -> Polarity.ACHIEVEMENT;   // WAKE 등
        };
    }

    private String failureReasonFor(VerificationMethod method, VerificationConfig config) {
        return switch (method) {
            case WAKE -> "WOKE_UP_LATE";
            case SCREEN_TIME -> "INSUFFICIENT_USAGE";
            case GPS_PRESENCE -> "INSUFFICIENT_DWELL";
            case GPS_DISTANCE -> "INSUFFICIENT_DISTANCE";
            case HEALTH -> healthFailureReason(config);
            case SLEEP -> "INSUFFICIENT_SLEEP";
            default -> "NO_SIGNAL_RECEIVED";
        };
    }

    private String healthFailureReason(VerificationConfig config) {
        if (config.health() != null && config.health().metric() != null) {
            return switch (config.health().metric()) {
                case STEPS -> "INSUFFICIENT_STEPS";
                default -> "INSUFFICIENT_DISTANCE";   // DISTANCE / EXERCISE_DURATION
            };
        }
        return "INSUFFICIENT_DISTANCE";
    }
}
