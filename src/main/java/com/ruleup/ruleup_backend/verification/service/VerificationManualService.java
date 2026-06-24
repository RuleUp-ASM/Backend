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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 수동 인증 제출(테크스펙 v2 §9, §11.6). 두 갈래:
 *  1) 정규 수동(PHOTO/SELF_CHECK): 자기증명 = 제출 즉시 SUCCESS(verifiedVia=MANUAL). 봉투·대상일·중복만 검증.
 *  2) 예비 폴백(asFallback=true): 자동인데 오늘 자동 불가일 때. 주1회(롤링7일)·잠정 SUCCESS·이의윈도우 확정(§9.2).
 *  - 사진 내용은 판단하지 않음(VLM 없음).
 */
@Service
@RequiredArgsConstructor
public class VerificationManualService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int FALLBACK_WEEKLY_LIMIT = 1;       // 주 1회(롤링 7일, §9.2)
    private static final Duration DISPUTE_WINDOW = Duration.ofHours(24); // 이의 윈도우(§9.2: 24h, 다음 새벽과 맞물림)

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
        boolean fallback = req.fallback();

        // 예비 폴백: 주1회 한도 — 초과면 그냥 실패(버튼 비활성 우회 방지, §9.2).
        if (fallback && !member.tryUseFallback(today, FALLBACK_WEEKLY_LIMIT)) {
            throw new BusinessException(ErrorCode.FALLBACK_LIMIT_EXCEEDED);
        }

        VerificationMethodResult mr = methodResultRepo
                .findByVerificationDailyIdAndMethod(daily.getId(), method).orElse(null);
        if (mr == null) {
            mr = VerificationMethodResult.create(daily.getId(), method, null, true);
        }
        Map<String, Object> evidence = new HashMap<>();
        if (isPhoto) evidence.put("imageUrl", req.imageUrl()); else evidence.put("selfCheck", true);
        if (fallback) evidence.put("fallback", true);
        mr.evaluate(VerificationStatus.SUCCESS, evidence, now);
        methodResultRepo.save(mr);

        Instant disputeClosesAt = null;
        if (fallback) {
            disputeClosesAt = now.plus(DISPUTE_WINDOW);
            daily.recordFallbackProvisional(method, disputeClosesAt);   // 잠정 SUCCESS + 이의윈도우
        } else {
            daily.recordManual(method, now);                            // 정규 수동 = 즉시 확정 SUCCESS
        }

        if (targetDate.equals(today)) progressService.updateAfterSync(member, VerificationStatus.SUCCESS, now);
        else progressService.recount(member);

        return new ManualVerificationResponse(
                targetDate.toString(), "SUCCESS",
                fallback ? "MANUAL_FALLBACK" : "MANUAL",
                (disputeClosesAt != null) ? disputeClosesAt.toString() : null,
                method, member.getProgressRate());
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
