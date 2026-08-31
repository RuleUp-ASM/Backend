package com.ruleup.ruleup_backend.verification.service;
import com.ruleup.ruleup_backend.common.verification.*;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.service.ChallengeQueryService;
import com.ruleup.ruleup_backend.challenge.stats.ChallengeStatsRefreshRequested;
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
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 인증 확정 배치 (인증 정책 §2 · 테크스펙 §4-3 "일일 확정 배치"). 두 작업:
 *  1) finalizeDue : <b>귀속일 이틀 뒤 00:00 KST</b>가 지난 미확정 행을 최종 재평가해 완료·실패로 확정한다.
 *     - 목표 달성형: 성공은 이미 즉시 확정됐으므로 여기 남은 건 미달 → 실패.
 *     - 규칙 지키기형: 위반이 남아 있으면 실패, 없으면 완료.
 *     이 시각 전에는 어떤 실패도 확정되지 않는다 — 늦게 도착하는 신호로 뒤집힐 수 있기 때문이다.
 *  2) rolloverFrequencyPeriods : 빈도형 주기 종료분을 미달 정산 + 다음 주기로 롤오버.
 *
 * <p>1분 주기로 도는 폴러지만 대상 조건이 {@code finalizeAfter <= now} 라, 실제 확정은 각 귀속일의
 * 이틀 뒤 00:00 KST 에만 일어난다. 귀속일 종료 후 하루는 늦게 도착하는 신호를 받는 유예 구간이고,
 * 유저는 그 사이 "이대로면 실패"를 보고 이의를 낸다. 폴러라서 배포·장애로 배치가 밀려도 스스로 따라잡고(catch-up),
 * 이미 확정된 건은 건너뛰므로 재실행이 안전하다.
 * FOR UPDATE SKIP LOCKED 선점이라 다중 인스턴스에서도 같은 대상을 중복 처리하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class VerificationFinalizeService {

    private static final Logger log = LoggerFactory.getLogger(VerificationFinalizeService.class);

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int CLAIM_LIMIT = 200;
    /** 한 번에 채울 무신호 대상 상한. 유저 2만 × 동시 3개 기준 일 6만 건이라 여유를 둔다. */
    private static final int MATERIALIZE_LIMIT = 100_000;

    private final VerificationDailyRepository dailyRepo;
    private final VerificationMethodResultRepository methodResultRepo;
    private final ChallengeQueryService challengeQuery;
    private final VerificationConfigFactory configFactory;
    private final VerificationProgressService progressService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 매일 00:00:30 KST: 확정 시각이 막 지난 귀속일(D-2)에 대해 <b>행이 없는 대상</b>을 채운다.
     *
     * <p>판정 행은 sync 가 만든다. 그래서 그날 앱을 한 번도 켜지 않은 사용자는 행이 아예 없고,
     * 확정 배치가 "PENDING 행"만 훑으면 그 날짜는 실패로도 확정되지 않아 통계에서 통째로 사라진다.
     * 여기서 빈 자리를 채워 두면 아래 {@link #finalizeDue()} 가 NO_SIGNAL_RECEIVED 로 확정한다.
     *
     * <p>대상 아닌 날(요일 밖·기간 밖·빈도 몫 충족)은 열지 않는다 — 확정되지 않을 행을 만들 이유가 없다.
     * 재실행해도 이미 있는 행은 건너뛰므로 안전하다.
     */
    @Scheduled(cron = "30 0 0 * * *", zone = "Asia/Seoul")
    @Transactional
    public void materializeDueTargets() {
        LocalDate targetDate = LocalDate.now(KST).minusDays(2);   // 확정 시각이 방금 지난 귀속일
        List<ChallengeMember> members = challengeQuery.findActiveOnDate(targetDate, MATERIALIZE_LIMIT);
        int opened = 0;
        for (ChallengeMember member : members) {
            if (dailyRepo.findByChallengeMemberIdAndTargetDate(member.getId(), targetDate).isPresent()) continue;
            Challenge challenge = challengeQuery.findChallenge(member.getChallengeId()).orElse(null);
            if (challenge == null) continue;
            VerificationConfig config = configFactory.build(challenge);
            if (config.isManual()) continue;   // 수동 인증은 미체크가 곧 미수행 — 자동 확정 대상이 아니다
            if (VerificationTargetDays.of(config, challenge, member, targetDate)
                    != VerificationTargetDays.Disposition.EVALUATE) {
                continue;
            }
            VerificationDaily daily = dailyRepo.save(VerificationDaily.open(
                    member.getId(), challenge.getId(), member.getUserId(), targetDate));
            daily.applyWindow(null);
            opened++;
        }
        if (opened > 0) log.info("무신호 귀속일 채우기: {} 대상 {}건 개시", targetDate, opened);
    }

    /** 1분마다 폴링하되, 실제 확정은 귀속일 이틀 뒤 00:00 KST 가 지난 건에서만 일어난다. */
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
            log.info("인증 확정 배치: 귀속일이 끝난 미확정 {}건 확정 처리", due.size());
        }
    }

    /**
     * 한 건 최종 재평가·확정. 확정 결과가 이미 있으면 건너뛴다(재실행 멱등).
     *
     * <p>재평가 입력은 그 날 누적된 방식 평가 결과다. 규칙 지키기형(장소 피하기·앱 최대 사용)의 위반과
     * 목표 달성형의 미달 사유는 sync 가 "실패 예정"으로 {@code failureReason} 에 눌러 담아 두므로,
     * 여기서는 그 사유가 확정 시각까지 살아남았는지만 보면 된다 — 성공은 이미 즉시 확정돼 여기 오지 않는다.
     */
    private boolean finalizeOne(VerificationDaily daily, Instant now) {
        if (daily.isTerminal()) return false;   // 다른 인스턴스가 먼저 확정 — 중복 확정 금지

        Challenge challenge = challengeQuery.findChallenge(daily.getChallengeId()).orElse(null);
        if (challenge == null) {
            daily.confirmFailure(now, daily.getMethod(), "NO_SIGNAL_RECEIVED");
            return false;
        }
        VerificationConfig config = configFactory.build(challenge);
        VerificationMethod method = config.primaryMethod();
        Polarity polarity = VerificationPolarity.of(config);

        boolean confirmedFail;
        if (polarity == Polarity.CONSTRAINT && daily.getFailureReason() == null) {
            // 정해진 기간 동안 유효한 위반이 없었다 → 완료 확정.
            daily.recordResult(VerificationStatus.SUCCESS, method.name(), null, now);
            confirmedFail = false;
        } else {
            daily.confirmFailure(now, method.name(), finalFailureReason(daily, method, config));
            confirmedFail = true;
        }

        ChallengeMember member = challengeQuery.findMember(daily.getChallengeMemberId()).orElse(null);
        refreshProgress(member, daily);

        // 확정된 실패만 감시자 통지 적재. 실패 예정 단계에서는 통지하지 않는다(확정이 아니므로).
        if (confirmedFail && member != null) {
            eventPublisher.publishEvent(new RoutineFailureConfirmed(
                    daily.getChallengeId(), member.getUserId(), daily.getId(),
                    daily.getTargetDate(), now));
        }
        return member != null;
    }

    /**
     * 최종 실패 사유. sync 가 남긴 "실패 예정" 사유가 있으면 그대로 쓰고,
     * 없으면 신호 자체가 없었던 경우(권한 공백 / 신뢰 게이트 탈락 / 무신호)를 구분해 붙인다.
     */
    private String finalFailureReason(VerificationDaily daily, VerificationMethod method, VerificationConfig config) {
        if (daily.getFailureReason() != null) return daily.getFailureReason();

        var mr = methodResultRepo.findByVerificationDailyIdAndMethod(daily.getId(), method.name()).orElse(null);
        if (mr == null) return "NO_SIGNAL_RECEIVED";
        Object pendingReason = (mr.getEvidence() != null) ? mr.getEvidence().get("pendingReason") : null;
        if ("UNTRUSTED_HEALTH_SOURCE".equals(pendingReason)) return "UNTRUSTED_HEALTH_SOURCE";
        if ("PERMISSION_MISSING".equals(pendingReason)) return "PERMISSION_MISSING";   // 무신호와 구분
        return failureReasonFor(method, config);
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
