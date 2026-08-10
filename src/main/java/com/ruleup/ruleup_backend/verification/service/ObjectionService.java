package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.service.ChallengeQueryService;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.event.RoutineFailureConfirmed;
import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.verification.domain.Objection;
import com.ruleup.ruleup_backend.verification.domain.ObjectionType;
import com.ruleup.ruleup_backend.verification.domain.VerificationDaily;
import com.ruleup.ruleup_backend.verification.dto.ObjectionDecisionRequest;
import com.ruleup.ruleup_backend.verification.dto.ObjectionDecisionResponse;
import com.ruleup.ruleup_backend.verification.dto.ObjectionResponse;
import com.ruleup.ruleup_backend.verification.dto.ObjectionSubmitRequest;
import com.ruleup.ruleup_backend.verification.repository.ObjectionRepository;
import com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 이의 제기(§8.7): 잠정 실패(FAILED_PROVISIONAL) 일자에 대한 제출 / 방장·공동 관리자 처리.
 *  - 제출: 멤버 본인, 잠정 실패 상태 + 1일 창 안, 일자당 1회. 솔로 챌린지는 대상 아님.
 *  - 처리: OWNER/MANAGER. 승인→SUCCESS(OBJECTION), 기각→FAILED(OBJECTION_REJECTED, 온도 반영).
 */
@Service
@RequiredArgsConstructor
public class ObjectionService {

    private final ObjectionRepository objectionRepo;
    private final VerificationDailyRepository dailyRepo;
    private final ChallengeQueryService challengeQuery;
    private final VerificationProgressService progressService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public ObjectionResponse submit(UUID userId, UUID challengeId, ObjectionSubmitRequest req) {
        Challenge c = challengeQuery.findActiveChallenge(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));

        // 참여 멤버 본인만.
        ChallengeMember member = challengeQuery.findMembership(challengeId, userId)
                .filter(ChallengeMember::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_CHALLENGE_MEMBER));

        // MVP는 FAILURE만.
        if (req.type() == null || !"FAILURE".equals(req.type()))
            throw new BusinessException(ErrorCode.UNSUPPORTED_OBJECTION_TYPE);
        if (req.content() == null || req.content().isBlank())
            throw new BusinessException(ErrorCode.CONTENT_REQUIRED);
        LocalDate date = parseDate(req.targetDate());

        // 솔로는 이의 제기 없음.
        if (!c.isGroup()) throw new BusinessException(ErrorCode.NOT_OBJECTIONABLE);

        // 잠정 실패 상태의 일자에만.
        VerificationDaily daily = dailyRepo.findByChallengeMemberIdAndTargetDate(member.getId(), date)
                .filter(VerificationDaily::isProvisionalFailure)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_OBJECTIONABLE));

        // 이의 제기 창(1일) 안.
        Instant now = Instant.now();
        if (daily.getDisputeClosesAt() == null || now.isAfter(daily.getDisputeClosesAt()))
            throw new BusinessException(ErrorCode.OBJECTION_WINDOW_CLOSED);

        // 일자당 1회.
        if (objectionRepo.existsByChallengeMemberIdAndTargetDate(member.getId(), date))
            throw new BusinessException(ErrorCode.ALREADY_OBJECTED);

        Objection o = objectionRepo.save(Objection.submit(
                challengeId, member.getId(), userId, date, ObjectionType.FAILURE,
                req.content(), req.imageUrl(), daily.getDisputeClosesAt()));
        return new ObjectionResponse(o.getId().toString(), o.getType().name(),
                o.getStatus().name(), o.getDeadline().toString());
    }

    @Transactional
    public ObjectionDecisionResponse decide(UUID adminId, UUID challengeId, UUID objectionId, ObjectionDecisionRequest req) {
        Challenge c = challengeQuery.findActiveChallenge(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
        if (!challengeQuery.isChallengeAdmin(c, adminId))
            throw new BusinessException(ErrorCode.NOT_CHALLENGE_ADMIN);

        Objection o = objectionRepo.findByIdAndChallengeId(objectionId, challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.OBJECTION_NOT_FOUND));
        if (!o.isPending()) throw new BusinessException(ErrorCode.ALREADY_DECIDED);

        VerificationDaily daily = dailyRepo.findByChallengeMemberIdAndTargetDate(o.getChallengeMemberId(), o.getTargetDate())
                .orElseThrow(() -> new BusinessException(ErrorCode.VERIFICATION_NOT_FOUND));
        ChallengeMember member = challengeQuery.findMember(o.getChallengeMemberId()).orElse(null);
        Instant now = Instant.now();

        String decision = (req.decision() == null) ? "" : req.decision();
        switch (decision) {
            case "APPROVE" -> {
                o.approve(adminId, now, req.reason());
                daily.approveObjection(now);   // SUCCESS(OBJECTION) — 잠정 실패는 온도 미반영이라 복원 불필요
                refreshProgress(member, daily);
                return new ObjectionDecisionResponse(o.getId().toString(), o.getStatus().name(),
                        o.getTargetDate().toString(), VerificationStatus.SUCCESS.name(), "OBJECTION");
            }
            case "REJECT" -> {
                o.reject(adminId, now, req.reason());
                daily.rejectObjection(now);    // FAILED(OBJECTION_REJECTED) — 확정, 온도 반영
                refreshProgress(member, daily);
                if (member != null) {
                    eventPublisher.publishEvent(new RoutineFailureConfirmed(
                            challengeId, member.getUserId(), o.getTargetDate(), now));
                }
                return new ObjectionDecisionResponse(o.getId().toString(), o.getStatus().name(),
                        o.getTargetDate().toString(), VerificationStatus.FAILED.name(), null);
            }
            default -> throw new BusinessException(ErrorCode.INVALID_DECISION);
        }
    }

    private void refreshProgress(ChallengeMember member, VerificationDaily daily) {
        if (member == null) return;
        if (daily.getTargetDate().equals(LocalDate.now(java.time.ZoneId.of("Asia/Seoul")))) {
            progressService.recountAndSetToday(member, daily.getStatus());
        } else {
            progressService.recount(member);
        }
    }

    private LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) throw new BusinessException(ErrorCode.INVALID_TARGET_DATE);
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INVALID_TARGET_DATE);
        }
    }
}
