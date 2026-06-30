package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.service.ChallengeQueryService;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/**
 * 예비 폴백(asFallback) 수동 인증의 방장 승인/거절(§9.2 / API .../verifications/{id}/approval).
 *  - APPROVE → SUCCESS(verifiedVia=MANUAL_FALLBACK), 진행률 갱신
 *  - REJECT  → FAILED(failureReason=FALLBACK_REJECTED)
 * 정규 수동(asFallback=false)은 승인 대상이 아니다(NOT_PENDING_APPROVAL).
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

    @Transactional
    public FallbackApprovalResponse decide(UUID ownerId, UUID challengeId, UUID verificationId,
                                           FallbackApprovalRequest req) {
        Challenge ch = challengeQuery.findActiveChallenge(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
        if (!ch.isOwner(ownerId)) throw new BusinessException(ErrorCode.NOT_CHALLENGE_OWNER);

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

        if (approve) {
            daily.approveFallback(now);
            if (mr != null) mr.evaluate(VerificationStatus.SUCCESS, mr.getEvidence(), now);
            // 승인 = 성공 확정 → 진행률 반영(대상일이 오늘이면 즉시, 아니면 재집계).
            if (daily.getTargetDate().equals(LocalDate.now(KST)))
                progressService.updateAfterSync(member, VerificationStatus.SUCCESS, now);
            else
                progressService.recount(member);
            notificationService.notify(member.getUserId(), NotificationType.FALLBACK_APPROVED,
                    "예비 인증이 승인되었어요",
                    daily.getTargetDate() + " 예비 인증이 방장 승인으로 인정되었어요.");
        } else {
            daily.rejectFallback(now);
            if (mr != null) mr.evaluate(VerificationStatus.FAILED, mr.getEvidence(), now);
            progressService.recount(member);   // 거절은 성공 미반영 → 진행률 현재값 유지
            notificationService.notify(member.getUserId(), NotificationType.FALLBACK_REJECTED,
                    "예비 인증이 거절되었어요",
                    daily.getTargetDate() + " 예비 인증이 방장에 의해 거절되었어요.");
        }

        return new FallbackApprovalResponse(
                daily.getId().toString(),
                daily.getTargetDate().toString(),
                daily.getStatus().name(),
                daily.getVerifiedVia() != null ? daily.getVerifiedVia().name() : null,
                daily.getFailureReason(),
                member.getProgressRate());
    }

    private boolean parseApprove(String decision) {
        if ("APPROVE".equalsIgnoreCase(decision)) return true;
        if ("REJECT".equalsIgnoreCase(decision)) return false;
        throw new BusinessException(ErrorCode.INVALID_REQUEST);
    }
}
