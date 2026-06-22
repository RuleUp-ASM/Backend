package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.verification.domain.VerificationConfig;
import com.ruleup.ruleup_backend.verification.domain.VerificationDaily;
import com.ruleup.ruleup_backend.verification.domain.VerificationMethodResult;
import com.ruleup.ruleup_backend.verification.domain.VerificationStatus;
import com.ruleup.ruleup_backend.verification.dto.ManualVerificationRequest;
import com.ruleup.ruleup_backend.verification.dto.ManualVerificationResponse;
import com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository;
import com.ruleup.ruleup_backend.verification.repository.VerificationMethodResultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

/**
 * 수동 인증 제출 (§3.4, VF-04). 자기증명 = 제출 즉시 SUCCESS.
 *  - 서버는 대상일·기간·중복만 검증(사진 내용·VLM 없음).
 *  - 무결성(가짜 신고·VOID)은 PN/RP 스펙 범위.
 */
@Service
@RequiredArgsConstructor
public class VerificationManualService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final ChallengeRepository challengeRepo;
    private final ChallengeMemberRepository memberRepo;
    private final VerificationDailyRepository dailyRepo;
    private final VerificationMethodResultRepository methodResultRepo;
    private final VerificationConfigFactory configFactory;
    private final VerificationMemberSetup memberSetup;
    private final VerificationProgressService progressService;

    @Transactional
    public ManualVerificationResponse submit(UUID userId, UUID challengeId, ManualVerificationRequest req) {
        Challenge ch = challengeRepo.findByIdAndDeletedAtIsNull(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
        ChallengeMember member = memberRepo.findByChallengeIdAndUserId(challengeId, userId).orElse(null);
        if (member == null || !member.isActive()) {
            throw new BusinessException(ErrorCode.NOT_CHALLENGE_MEMBER);
        }

        boolean isPhoto = "PHOTO".equalsIgnoreCase(req.method());
        String method = isPhoto ? "PHOTO" : "SELF_CHECK";
        if (isPhoto && (req.imageUrl() == null || req.imageUrl().isBlank())) {
            throw new BusinessException(ErrorCode.IMAGE_REQUIRED);
        }

        LocalDate today = LocalDate.now(KST);
        LocalDate targetDate = parseTargetDate(req.targetDate(), today);
        if (targetDate.isBefore(ch.getStartDate()) || targetDate.isAfter(ch.getEndDate())) {
            throw new BusinessException(ErrorCode.INVALID_TARGET_DATE);
        }

        if (member.getTargetDays() == 0) {
            VerificationConfig config = configFactory.build(ch);
            memberSetup.apply(member, ch, config);
        }

        VerificationDaily daily = dailyRepo.findByChallengeMemberIdAndTargetDate(member.getId(), targetDate)
                .orElseGet(() -> dailyRepo.save(
                        VerificationDaily.open(member.getId(), ch.getId(), member.getUserId(), targetDate)));
        if (daily.getStatus() == VerificationStatus.SUCCESS) {
            throw new BusinessException(ErrorCode.ALREADY_VERIFIED);
        }

        Instant now = Instant.now();
        VerificationMethodResult mr = methodResultRepo
                .findByVerificationDailyIdAndMethod(daily.getId(), method).orElse(null);
        if (mr == null) {
            mr = VerificationMethodResult.create(daily.getId(), method, null, true);
        }
        Map<String, Object> evidence = isPhoto
                ? Map.of("imageUrl", req.imageUrl()) : Map.of("selfCheck", true);
        mr.evaluate(VerificationStatus.SUCCESS, evidence, now);
        methodResultRepo.save(mr);

        daily.recordResult(VerificationStatus.SUCCESS, method, null, now);

        if (targetDate.equals(today)) progressService.updateAfterSync(member, VerificationStatus.SUCCESS, now);
        else progressService.recount(member);

        return new ManualVerificationResponse(targetDate.toString(), "SUCCESS", method, member.getProgressRate());
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
