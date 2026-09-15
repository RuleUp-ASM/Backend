package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeStatus;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.service.ChallengeQueryService;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.verification.domain.FailureEvidence;
import com.ruleup.ruleup_backend.verification.domain.FailureReasons;
import com.ruleup.ruleup_backend.verification.domain.GapReason;
import com.ruleup.ruleup_backend.verification.domain.Polarity;
import com.ruleup.ruleup_backend.verification.domain.VerificationMethod;
import com.ruleup.ruleup_backend.verification.domain.VerificationMethodResult;
import com.ruleup.ruleup_backend.verification.domain.VerificationConfig;
import com.ruleup.ruleup_backend.verification.domain.VerificationPolarity;
import com.ruleup.ruleup_backend.verification.domain.VerificationDaily;
import com.ruleup.ruleup_backend.verification.dto.ChallengeProgress;
import com.ruleup.ruleup_backend.verification.dto.TodayVerificationResponse;
import com.ruleup.ruleup_backend.verification.repository.AppealRepository;
import com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository;
import com.ruleup.ruleup_backend.verification.repository.VerificationFailureDetailRepository;
import com.ruleup.ruleup_backend.verification.repository.VerificationMethodResultRepository;
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
import java.util.Map;
import java.util.Set;
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

    private final ChallengeQueryService challengeQuery;
    private final com.ruleup.ruleup_backend.challenge.view.ChallengeMasking masking;
    private final VerificationDailyRepository dailyRepo;
    private final VerificationMethodResultRepository methodResultRepo;
    private final VerificationFailureDetailRepository failureDetailRepo;
    private final AppealRepository appealRepo;
    private final VerificationConfigFactory configFactory;
    private final StreakService streakService;

    // ===== GET /api/v1/verifications/progress — 진행률 일괄 =====

    /**
     * 내 챌린지 진행률.
     *
     * <p>{@code ACTIVE} 는 <b>지금 진행 중인 방</b>이다. 그런데 방이 끝나도 멤버십은 ACTIVE 로
     * 남는다 — 종료 배치는 방의 상태 축만 마감하고 멤버를 건드리지 않는다(완주율·최종 랭킹이
     * 멤버 행을 그대로 읽어야 하기 때문이다). 그래서 멤버십만 보고 거르면 <b>끝난 방이 홈의
     * 진행률 목록에 계속 남는다</b>.
     *
     * <p>{@code findActiveChallenge} 는 이름과 달리 소프트 삭제만 거른다. 그 이름을 믿고 한 번 더
     * 거르지 않은 것이 이 버그였다.
     */
    public List<ChallengeProgress> progress(UUID userId, String statusFilter) {
        boolean all = "ALL".equalsIgnoreCase(statusFilter);
        List<ChallengeMember> members = all
                ? challengeQuery.findAllMemberships(userId)
                : challengeQuery.findActiveMemberships(userId);
        LocalDate today = LocalDate.now(KST);
        // 목록이라 차단 집합을 한 번만 읽는다 — 항목마다 물으면 화면 한 장에 왕복이 수십 번이다.
        Set<UUID> masked = masking.maskedFor(userId);
        List<ChallengeProgress> out = new ArrayList<>();
        for (ChallengeMember m : members) {
            Challenge ch = challengeQuery.findActiveChallenge(m.getChallengeId()).orElse(null);
            if (ch == null) continue;
            // 시작 전(UPCOMING)은 남긴다 — 곧 시작할 방도 「내 챌린지」에 보여야 한다.
            if (!all && ch.getStatus() == ChallengeStatus.COMPLETED) continue;
            out.add(toProgress(m, ch, configFactory.build(ch), today,
                    masked.contains(ch.getId()), ch.isOwner(userId)));
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

        Polarity polarity = VerificationPolarity.of(config);
        boolean isTarget = isTodayTarget(config, ch, member, today);
        String status = todayStatus(isTarget, daily, today, polarity, now);
        // 실패 확정과 실패 예정 모두 실패 사유·이의 안내를 실어 준다 —
        // 이의는 확정 전에 받으므로 실패 예정 구간이 실제 신청 창이다.
        boolean failing = TodayStatusView.FAILED.equals(status)
                || TodayStatusView.FAIL_EXPECTED.equals(status);

        // 실패 사유·판정 근거는 실패(예정)일 때만 만든다 — 조회 한 번이 더 필요해서
        // 진행중 카드에는 붙이지 않는다.
        Failure failure = (failing && daily != null) ? failureOf(daily, config) : Failure.NONE;

        return new TodayVerificationResponse(
                (daily != null) ? daily.getId().toString() : null,
                today.toString(),
                status,
                TodayStatusView.NOT_TARGET.equals(status) ? null : windowLabel(config),
                failure.gapReason(),
                (daily != null) ? formatKst(daily.getVerifiedAt()) : null,
                failure.reasonCode(),
                failure.summary(),
                streakService.around(member.getId(), today),
                unacknowledged(daily),
                failing ? appeal(member, daily, polarity, now) : null);
    }

    /**
     * 실패 설명 — <b>확정된 실패는 판정 당시 스냅샷을, 실패 예정은 지금 신호로 계산</b>한다.
     *
     * <p>실패 예정에 상세 행을 만들지 않는 이유가 여기 드러난다. 그 상태는 늦게 도착한 신호나
     * 이의로 뒤집힐 수 있어서, 저장해 두면 완료가 된 뒤에도 실패 기록이 남는다. 대신 화면에
     * 보여줄 근거는 그때그때 계산한다 — 유저는 이 숫자를 보고 이의를 낸다(공통 5-8).
     */
    private Failure failureOf(VerificationDaily daily, VerificationConfig config) {
        if (daily.getStatus() == VerificationStatus.FAILED) {
            String summary = failureDetailRepo.findById(daily.getId())
                    .map(d -> d.getEvidenceSummary()).orElse(null);
            if (summary != null) {
                return new Failure(daily.getFailureReason(), daily.getGapReason(), summary);
            }
        }
        VerificationMethod method = config.primaryMethod();
        Map<String, Object> evidence = (method == null) ? null
                : methodResultRepo.findByVerificationDailyIdAndMethod(daily.getId(), method.name())
                .map(VerificationMethodResult::getEvidence).orElse(null);

        // 목표 미달은 확정 전까지 사유가 비어 있다 — 확정 배치와 같은 규칙으로 채워야
        // 같은 사건이 화면에서 두 번 다르게 설명되지 않는다.
        String pending = FailureReasons.pendingReasonOf(evidence);
        String reasonCode = (daily.getFailureReason() != null) ? daily.getFailureReason()
                : (pending != null) ? pending : FailureReasons.of(method, config);
        String gapReason = (daily.getGapReason() != null)
                ? daily.getGapReason() : GapReason.of(reasonCode);

        return new Failure(reasonCode, gapReason, FailureEvidence.of(reasonCode, evidence).summary());
    }

    /** 실패(예정) 한 건의 설명 세 값. 진행중이면 전부 null 이다. */
    private record Failure(String reasonCode, String gapReason, String summary) {
        static final Failure NONE = new Failure(null, null, null);
    }

    // ===== 조립 헬퍼 =====

    /** 오늘이 대상 날짜가 아니면 판정 행과 무관하게 NOT_TARGET. 나머지는 공용 매핑(TodayStatusView). */
    private String todayStatus(boolean isTarget, VerificationDaily daily, LocalDate today,
                               Polarity polarity, Instant now) {
        if (!isTarget) return TodayStatusView.NOT_TARGET;
        if (daily == null) return TodayStatusView.IN_PROGRESS;
        return TodayStatusView.of(daily.getStatus(), today, daily.getFailureReason(), polarity, now);
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
     * 이의 신청 가능 여부와 기한. 기한은 확정 시각과 같은 <b>귀속일 이틀 뒤 00:00 KST</b>로 고정된
     * 자정 경계다(인증 정책 §5.2) — 확정 시각 기준 상대 24시간이 아니다.
     * 이의는 확정 <b>전에</b> 받으므로, 유예 하루 동안의 "실패 예정" 구간이 실제 신청 창이다.
     * 횟수 한도는 없고, 솔로·그룹을 가리지 않는다 — 자동 판정이 틀리는 건 어느 쪽에서나 같다.
     */
    private TodayVerificationResponse.Appeal appeal(ChallengeMember member, VerificationDaily daily,
                                                    Polarity polarity, Instant now) {
        if (daily == null || daily.getAppealClosesAt() == null) return null;
        boolean alreadyFiled = appealRepo.existsByVerificationDailyId(daily.getId());
        return new TodayVerificationResponse.Appeal(
                ZonedDateTime.ofInstant(daily.getAppealClosesAt(), KST).format(ISO_OFFSET),
                !alreadyFiled && daily.isAppealable(polarity, now));
    }

    private ChallengeProgress toProgress(ChallengeMember m, Challenge ch, VerificationConfig config,
                                         LocalDate today, boolean masked, boolean viewerIsOwner) {
        boolean freq = config.isFrequency();
        int remaining = freq
                ? Math.max(m.getTargetDays() - m.getSuccessDays(), 0)
                : Math.max(m.getTargetDays() - m.getSuccessDays() - m.getFailDays(), 0);
        return new ChallengeProgress(
                ch.getId().toString(),
                com.ruleup.ruleup_backend.challenge.view.ChallengeView.of(ch, viewerIsOwner, masked).title(),
                ch.getCategory(), ch.getParticipationType().name(),
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
