package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.service.ChallengeQueryService;
import com.ruleup.ruleup_backend.challenge.stats.ChallengeStatsRefreshRequested;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.verification.domain.VerificationConfig;
import com.ruleup.ruleup_backend.verification.domain.VerificationDaily;
import com.ruleup.ruleup_backend.verification.domain.VerificationMethod;
import com.ruleup.ruleup_backend.verification.domain.VerificationMethodResult;
import com.ruleup.ruleup_backend.verification.dto.ManualVerificationRequest;
import com.ruleup.ruleup_backend.verification.dto.ManualVerificationResponse;
import com.ruleup.ruleup_backend.verification.dto.StreakChange;
import com.ruleup.ruleup_backend.verification.dto.VerificationCancelResponse;
import com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository;
import com.ruleup.ruleup_backend.verification.repository.VerificationMethodResultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 수동 인증(자체 체크) 제출·취소.
 *
 * <p><b>수동 방에서만</b> 쓴다 — 자동 방의 수동 폴백은 폐기됐고, 자동 방의 실패 구제는 이의 제기가 담당한다.
 * 자동 방에서 부르면 NOT_MANUAL_CHALLENGE.
 *
 * <p>별도 부정 방지 장치는 두지 않는다 — 제출 즉시 인정(치팅 가능성은 정책적으로 수용, AI 호출 비용도 안 쓴다).
 * 대신 <b>당일(KST) 마감</b>이라 날짜가 지나면 체크도 취소도 불가하다.
 * 점수 패널티는 수동 방 고정 OFF라 점수 변동이 없지만(scoreNote=MANUAL_NO_SCORE),
 * 성공률·랭킹·통계에는 포함된다.
 */
@Service
@RequiredArgsConstructor
public class VerificationManualService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final ChallengeQueryService challengeQuery;
    private final VerificationDailyRepository dailyRepo;
    private final VerificationMethodResultRepository methodResultRepo;
    private final VerificationConfigFactory configFactory;
    private final VerificationMemberSetup memberSetup;
    private final VerificationProgressService progressService;
    private final StreakService streakService;
    private final ApplicationEventPublisher eventPublisher;

    // ===== POST /api/v1/challenges/{challengeId}/verifications =====
    @Transactional
    public ManualVerificationResponse submit(UUID userId, UUID challengeId, ManualVerificationRequest req) {
        Challenge ch = challengeQuery.findActiveChallenge(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
        ChallengeMember member = activeMember(challengeId, userId);

        VerificationConfig config = configFactory.build(ch);
        if (!config.isManual()) {
            throw new BusinessException(ErrorCode.NOT_MANUAL_CHALLENGE);
        }

        // 당일 마감 — targetDate 는 오늘만 허용한다(생략하면 오늘).
        LocalDate today = LocalDate.now(KST);
        LocalDate targetDate = parseTargetDate(req != null ? req.targetDate() : null, today);
        if (!targetDate.equals(today)
                || targetDate.isBefore(ch.getStartDate()) || targetDate.isAfter(ch.getEndDate())) {
            throw new BusinessException(ErrorCode.INVALID_TARGET_DATE);
        }

        if (member.getTargetDays() == 0) memberSetup.apply(member, ch, config);

        VerificationDaily daily = dailyRepo.findByChallengeMemberIdAndTargetDate(member.getId(), targetDate)
                .orElseGet(() -> dailyRepo.save(
                        VerificationDaily.open(member.getId(), ch.getId(), member.getUserId(), targetDate)));
        if (daily.getStatus() == VerificationStatus.SUCCESS) {
            throw new BusinessException(ErrorCode.ALREADY_VERIFIED);       // 하루 1회
        }

        int streakBefore = streakService.around(member.getId(), targetDate).before();

        String method = VerificationMethod.SELF_CHECK.name();
        Instant now = Instant.now();
        VerificationMethodResult mr = methodResultRepo
                .findByVerificationDailyIdAndMethod(daily.getId(), method)
                .orElseGet(() -> VerificationMethodResult.create(daily.getId(), method, null, true));
        Map<String, Object> evidence = new HashMap<>();
        evidence.put("selfCheck", true);
        if (req != null && req.note() != null && !req.note().isBlank()) evidence.put("note", req.note());
        mr.evaluate(VerificationStatus.SUCCESS, evidence, now);
        methodResultRepo.save(mr);

        daily.recordManual(method, now);
        daily.acknowledge(now);   // 본인이 직접 체크한 결과라 확인할 모달이 없다
        progressService.updateAfterSync(member, VerificationStatus.SUCCESS, now);
        eventPublisher.publishEvent(ChallengeStatsRefreshRequested.of(challengeId, "MANUAL_SUCCESS"));

        return new ManualVerificationResponse(
                daily.getId().toString(), targetDate.toString(), "DONE",
                new StreakChange(streakBefore, streakBefore + 1),
                ManualVerificationResponse.MANUAL_NO_SCORE);
    }

    // ===== DELETE /api/v1/verifications/{verificationId} =====
    /**
     * 수동 체크 취소 — "당일 마감" 정책의 취소 경로. 해당 날짜(KST)가 지나면 불가하고,
     * 자동 판정 건은 대상이 아니다. 취소하면 그 날짜는 다시 IN_PROGRESS 로 돌아간다.
     */
    @Transactional
    public VerificationCancelResponse cancel(UUID userId, UUID verificationId) {
        VerificationDaily daily = dailyRepo.findById(verificationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VERIFICATION_NOT_FOUND));
        if (!daily.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.VERIFICATION_NOT_FOUND);   // 본인 건이 아님 — 존재를 알리지 않는다
        }
        if (!daily.isManualVerification()) {
            throw new BusinessException(ErrorCode.NOT_MANUAL_VERIFICATION);
        }
        if (!daily.getTargetDate().equals(LocalDate.now(KST))) {
            throw new BusinessException(ErrorCode.CANCEL_WINDOW_CLOSED);
        }

        methodResultRepo.findByVerificationDailyIdAndMethod(
                        daily.getId(), VerificationMethod.SELF_CHECK.name())
                .ifPresent(methodResultRepo::delete);
        daily.cancelManual();

        ChallengeMember member = challengeQuery.findMembership(daily.getChallengeId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_CHALLENGE_MEMBER));
        progressService.recountAndSetToday(member, VerificationStatus.PENDING);
        eventPublisher.publishEvent(
                ChallengeStatsRefreshRequested.of(daily.getChallengeId(), "MANUAL_CANCELED"));

        return new VerificationCancelResponse(true);
    }

    private ChallengeMember activeMember(UUID challengeId, UUID userId) {
        ChallengeMember member = challengeQuery.findMembership(challengeId, userId).orElse(null);
        if (member == null || !member.isActive()) {
            throw new BusinessException(ErrorCode.NOT_CHALLENGE_MEMBER);
        }
        return member;
    }

    private LocalDate parseTargetDate(String raw, LocalDate today) {
        if (raw == null || raw.isBlank()) return today;
        try {
            return LocalDate.parse(raw);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INVALID_TARGET_DATE);
        }
    }
}
