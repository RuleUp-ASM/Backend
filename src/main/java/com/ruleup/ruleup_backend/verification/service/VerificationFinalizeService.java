package com.ruleup.ruleup_backend.verification.service;
import com.ruleup.ruleup_backend.common.verification.*;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.service.ChallengeQueryService;
import com.ruleup.ruleup_backend.verification.domain.*;
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
import java.util.List;

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
        for (VerificationDaily daily : due) {
            finalizeOne(daily, now);
        }
        if (!due.isEmpty()) {
            log.info("인증 확정 배치: 유예 끝난 PENDING {}건 확정 처리", due.size());
        }
    }

    private void finalizeOne(VerificationDaily daily, Instant now) {
        Challenge challenge = challengeQuery.findChallenge(daily.getChallengeId()).orElse(null);
        if (challenge == null) {
            daily.recordResult(VerificationStatus.FAILED, null, "NO_SIGNAL_RECEIVED", now);
            return;
        }
        VerificationConfig config = configFactory.build(challenge);
        VerificationMethod method = config.primaryMethod();
        Polarity polarity = polarityOf(config, method);

        boolean failed = false;
        if (polarity == Polarity.CONSTRAINT) {
            // 제약형(MAX·AVOID): 위반이면 sync에서 이미 FAILED 잠김 → 여기 온 건 무위반 → SUCCESS
            daily.recordResult(VerificationStatus.SUCCESS, method.name(), null, now);
        } else {
            // 도달형: 창 닫힘·미충족 → FAILED. 신호 자체가 없었으면 NO_SIGNAL_RECEIVED.
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
            daily.recordResult(VerificationStatus.FAILED, null, reason, now);
            failed = true;
        }

        ChallengeMember member = challengeQuery.findMember(daily.getChallengeMemberId()).orElse(null);
        if (member != null) progressService.recount(member);

        // 실패 확정 → 감시자 통지 적재(§9). watcher 리스너가 같은 트랜잭션에서 큐만 적재(외부 발송은 별도 스윕).
        if (failed && member != null) {
            eventPublisher.publishEvent(new RoutineFailureConfirmed(
                    daily.getChallengeId(), member.getUserId(), daily.getTargetDate(), now));
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
        for (ChallengeMember m : members) {
            Challenge ch = challengeQuery.findActiveChallenge(m.getChallengeId()).orElse(null);
            if (ch == null) continue;
            rolloverMember(m, ch, today);
            progressService.recount(m);
        }
        if (!members.isEmpty()) {
            log.info("빈도형 주기 롤오버: 대상 {}건 정산", members.size());
        }
    }

    /** 종료된 주기를 따라잡으며 정산(다운타임으로 여러 주기가 밀렸어도 catch-up). */
    private void rolloverMember(ChallengeMember m, Challenge ch, LocalDate today) {
        int guard = 0;
        while (m.getCurPeriodEnd() != null && m.getCurPeriodEnd().isBefore(today) && guard++ < 400) {
            int need = (m.getPeriodTarget() != null) ? m.getPeriodTarget() : 0;
            int done = (m.getCurPeriodCompleted() != null) ? m.getCurPeriodCompleted() : 0;
            int shortfall = Math.max(need - done, 0);

            LocalDate nextStart = m.getCurPeriodEnd().plusDays(1);
            if (nextStart.isAfter(ch.getEndDate())) {
                // 챌린지 종료: 마지막 주기 미달만 정산하고 advance 안 함(루프 종료)
                m.rolloverPeriod(m.getCurPeriodStart(), m.getCurPeriodEnd(), shortfall);
                break;
            }
            int periodDays = (m.getPeriodUnit() == PeriodUnit.WEEK) ? 7 : 30;
            LocalDate nextEnd = nextStart.plusDays(periodDays - 1L);
            if (nextEnd.isAfter(ch.getEndDate())) nextEnd = ch.getEndDate();
            m.rolloverPeriod(nextStart, nextEnd, shortfall);
        }
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
