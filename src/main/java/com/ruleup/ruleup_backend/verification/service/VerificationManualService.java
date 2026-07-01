package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.service.ChallengeQueryService;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.verification.domain.VerificationConfig;
import com.ruleup.ruleup_backend.verification.domain.VerificationDaily;
import com.ruleup.ruleup_backend.verification.domain.VerificationMethodResult;
import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
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

    private final ChallengeQueryService challengeQuery;
    private final VerificationDailyRepository dailyRepo;
    private final VerificationMethodResultRepository methodResultRepo;
    private final VerificationConfigFactory configFactory;
    private final VerificationMemberSetup memberSetup;
    private final VerificationProgressService progressService;

    @Transactional
    public ManualVerificationResponse submit(UUID userId, UUID challengeId, ManualVerificationRequest req) {
        Challenge ch = challengeQuery.findActiveChallenge(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
        ChallengeMember member = challengeQuery.findMembership(challengeId, userId).orElse(null);
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
        // 폴백은 방장 승인 전이라 잠정. 방식 결과도 PENDING으로 두고, 승인 시 SUCCESS로 확정.
        mr.evaluate(fallback ? VerificationStatus.PENDING : VerificationStatus.SUCCESS, evidence, now);
        methodResultRepo.save(mr);

        if (fallback) {
            // 방장 승인 모델(§9.2): PENDING_APPROVAL 로 적재, 진행률은 승인 전이라 현재값 유지.
            daily.recordFallbackPending(method);
            progressService.recount(member);   // PENDING은 성공 미집계 → 진행률 유지
            return new ManualVerificationResponse(
                    daily.getId().toString(), targetDate.toString(), "PENDING_APPROVAL",
                    method, "PENDING", null, null, member.getProgressRate());
        }

        // 정규 수동 = 즉시 확정 SUCCESS.
        daily.recordManual(method, now);
        if (targetDate.equals(today)) progressService.updateAfterSync(member, VerificationStatus.SUCCESS, now);
        else progressService.recount(member);
        return new ManualVerificationResponse(
                daily.getId().toString(), targetDate.toString(), "SUCCESS",
                method, null, "MANUAL", null, member.getProgressRate());
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
