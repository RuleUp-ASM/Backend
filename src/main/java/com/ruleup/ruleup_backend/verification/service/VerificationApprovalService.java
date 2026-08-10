package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.service.ChallengeQueryService;
import com.ruleup.ruleup_backend.challenge.stats.ChallengeStatsRefreshRequested;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.notification.NotificationService;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import com.ruleup.ruleup_backend.verification.domain.FallbackApprovalStatus;
import com.ruleup.ruleup_backend.verification.domain.VerificationDaily;
import com.ruleup.ruleup_backend.verification.domain.VerificationMethodResult;
import com.ruleup.ruleup_backend.verification.dto.FallbackApprovalRequest;
import com.ruleup.ruleup_backend.verification.dto.FallbackApprovalResponse;
import com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository;
import com.ruleup.ruleup_backend.verification.repository.VerificationMethodResultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/**
 * 그룹 예비 폴백 수동 인증의 승인/거절(§10.2 / API .../verifications/{id}/approval). 방장(OWNER)/공동 관리자(MANAGER).
 *  - APPROVE → SUCCESS(verifiedVia=MANUAL_FALLBACK), 진행률 갱신(잠정 실패였다면 대체)
 *  - REJECT  → 제출만 기각, 일자 판정은 기존(자동) 경로 복귀(실패 확정 아님). failureReason=FALLBACK_REJECTED 표시.
 * 정규 수동·솔로 폴백(즉시 SUCCESS)은 승인 대상이 아니다(NOT_PENDING_APPROVAL).
 */
@Service
@RequiredArgsConstructor
public class VerificationApprovalService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final ChallengeQueryService challengeQuery;
    private final VerificationDailyRepository dailyRepo;
    private final VerificationMethodResultRepository methodResultRepo;
    private final VerificationProgressService progressService;
    private final NotificationService notificationService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public FallbackApprovalResponse decide(UUID ownerId, UUID challengeId, UUID verificationId,
                                           FallbackApprovalRequest req) {
        Challenge ch = challengeQuery.findActiveChallenge(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
        if (!challengeQuery.isChallengeAdmin(ch, ownerId))
            throw new BusinessException(ErrorCode.NOT_CHALLENGE_ADMIN);

        VerificationDaily daily = dailyRepo.findById(verificationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VERIFICATION_NOT_FOUND));
        if (!daily.getChallengeId().equals(challengeId))
            throw new BusinessException(ErrorCode.VERIFICATION_NOT_FOUND);

        FallbackApprovalStatus st = daily.getFallbackApprovalStatus();
        if (st == null) throw new BusinessException(ErrorCode.NOT_PENDING_APPROVAL);
        if (st != FallbackApprovalStatus.PENDING) throw new BusinessException(ErrorCode.ALREADY_DECIDED);

        boolean approve = parseApprove(req == null ? null : req.decision());
        Instant now = Instant.now();

        ChallengeMember member = challengeQuery.findMember(daily.getChallengeMemberId())
                .orElseThrow(() -> new BusinessException(ErrorCode.VERIFICATION_NOT_FOUND));
        VerificationMethodResult mr = methodResultRepo
                .findByVerificationDailyIdAndMethod(daily.getId(), daily.getMethod()).orElse(null);

        String failureReason;
        if (approve) {
            daily.approveFallback(now);
            if (mr != null) mr.evaluate(VerificationStatus.SUCCESS, mr.getEvidence(), now);
            failureReason = null;
            // 승인 = 성공 확정 → 진행률 반영(대상일이 오늘이면 즉시, 아니면 재집계).
            if (daily.getTargetDate().equals(LocalDate.now(KST)))
                progressService.recountAndSetToday(member, VerificationStatus.SUCCESS);
            else
                progressService.recount(member);
            eventPublisher.publishEvent(ChallengeStatsRefreshRequested.of(challengeId, "FALLBACK_APPROVED"));
            notificationService.notify(member.getUserId(), NotificationType.FALLBACK_APPROVED,
                    "예비 인증이 승인되었어요",
                    daily.getTargetDate() + " 예비 인증이 방장 승인으로 인정되었어요.");
        } else {
            // 기각 = 제출만 기각, 일자 판정은 기존 경로 복귀(PENDING) — 그날이 실패로 확정되는 것이 아님(§10.2).
            daily.rejectFallbackSubmission();
            if (mr != null) mr.evaluate(VerificationStatus.PENDING, mr.getEvidence(), now);
            failureReason = "FALLBACK_REJECTED";   // 제출 기각 사유(표시용) — 일자 status 는 PENDING
            progressService.recount(member);
            notificationService.notify(member.getUserId(), NotificationType.FALLBACK_REJECTED,
                    "예비 인증이 거절되었어요",
                    daily.getTargetDate() + " 예비 인증 제출이 거절되었어요. 자동 판정 경로로 돌아갑니다.");
        }

        return new FallbackApprovalResponse(
                daily.getId().toString(),
                daily.getTargetDate().toString(),
                approve ? "SUCCESS" : "REJECTED",
                daily.getVerifiedVia() != null ? daily.getVerifiedVia().name() : null,
                failureReason,
                member.getProgressRate());
    }

    private boolean parseApprove(String decision) {
        if ("APPROVE".equalsIgnoreCase(decision)) return true;
        if ("REJECT".equalsIgnoreCase(decision)) return false;
        throw new BusinessException(ErrorCode.INVALID_DECISION);
    }
}
