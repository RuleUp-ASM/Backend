package com.ruleup.ruleup_backend.challenge.recommendation;

import com.ruleup.ruleup_backend.challenge.domain.*;
import com.ruleup.ruleup_backend.challenge.dto.RecommendationRequest;
import com.ruleup.ruleup_backend.challenge.dto.RecommendationResponse;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.user.InterestCategory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * 챌린지 기본값 추천 (스펙 3.1). 상태 저장 없음 — 트랜잭션 없이 동작.
 *
 * 흐름: 입력 검증 → Gemini 호출(트랜잭션 밖) → "신뢰 경계 보정" → 응답.
 * LLM은 신뢰 경계 밖이라(스펙 5), 잘못된 enum/숫자는 안전한 기본값으로 대체한다.
 * "다시 추천"도 이 메서드 재호출(스펙 3.1).
 */
@Service
@RequiredArgsConstructor
public class ChallengeRecommendationService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    // 보정 기본값
    private static final int DEFAULT_DURATION = 14;
    private static final BigDecimal DEFAULT_MIN_MANNER = new BigDecimal("65.0");
    private static final BigDecimal DEFAULT_MANNER_DEDUCTION = new BigDecimal("0.5");
    private static final BigDecimal DEFAULT_MANNER_GAIN = new BigDecimal("0.6");
    private static final List<String> DEFAULT_REPEAT_DAYS = List.of("MON","TUE","WED","THU","FRI");
    private static final List<String> DEFAULT_VERIFICATIONS = List.of(VerificationMethod.SELF_CHECK.name());

    private final GeminiRecommendationClient geminiClient;

    public RecommendationResponse recommend(RecommendationRequest req) {
        validateInput(req);

        // 트랜잭션 밖 외부 호출. 실패 시 AI_RECOMMENDATION_FAILED(503)가 그대로 전파됨.
        GeminiSuggestion s = geminiClient.recommend(req.title(), req.description());

        // ---- 신뢰 경계 보정 ----
        String category = sanitizeCategory(s.category());
        ParticipationType participationType = sanitizeParticipationType(s.participationType());

        BigDecimal minManner = (participationType == ParticipationType.GROUP)
                ? sanitizeMinManner(s.minMannerTemperature())
                : null;

        List<String> repeatDays = RepeatDay.allValid(s.repeatDays()) && !s.repeatDays().isEmpty()
                ? s.repeatDays() : DEFAULT_REPEAT_DAYS;

        int durationDays = (s.durationDays() != null && s.durationDays() >= 1)
                ? s.durationDays() : DEFAULT_DURATION;

        List<String> verifications = (VerificationMethod.allValid(s.verificationMethods())
                && s.verificationMethods() != null && !s.verificationMethods().isEmpty())
                ? s.verificationMethods() : DEFAULT_VERIFICATIONS;

        PenaltyConfig penalty = new PenaltyConfig(
                positiveOrDefault(s.mannerDeduction(), DEFAULT_MANNER_DEDUCTION),
                new PenaltyConfig.SnsShare(Boolean.TRUE.equals(s.snsShare()), null),
                Boolean.TRUE.equals(s.groupShare()));

        RewardConfig reward = new RewardConfig(positiveOrDefault(s.mannerGain(), DEFAULT_MANNER_GAIN));

        String refinedTitle = (s.refinedTitle() != null && !s.refinedTitle().isBlank())
                ? trimTo(s.refinedTitle(), 30) : req.title();
        String description = (s.description() != null && !s.description().isBlank())
                ? trimTo(s.description(), 200) : req.description();

        LocalDate start = LocalDate.now(KST);
        LocalDate end = start.plusDays((long) durationDays - 1);

        return new RecommendationResponse(
                refinedTitle, description, category, participationType.name(), minManner,
                repeatDays, durationDays, start.toString(), end.toString(),
                verifications, penalty, reward);
    }

    // ===== 입력 검증 (3.1 실패코드) =====
    private void validateInput(RecommendationRequest req) {
        if (req == null || req.title() == null || req.title().isBlank())
            throw new BusinessException(ErrorCode.TITLE_REQUIRED);
        if (req.title().length() > 30)
            throw new BusinessException(ErrorCode.TITLE_TOO_LONG);
        if (req.description() != null && req.description().length() > 200)
            throw new BusinessException(ErrorCode.DESCRIPTION_TOO_LONG);
    }

    // ===== 보정 헬퍼 =====
    private String sanitizeCategory(String code) {
        return (code != null && InterestCategory.allValid(List.of(code)))
                ? code : InterestCategory.HOBBY.name();   // 미정의 코드는 안전한 기본 카테고리
    }

    private ParticipationType sanitizeParticipationType(String v) {
        try {
            return ParticipationType.valueOf(v);
        } catch (Exception e) {
            return ParticipationType.SOLO;
        }
    }

    private BigDecimal sanitizeMinManner(BigDecimal v) {
        if (v == null) return DEFAULT_MIN_MANNER;
        if (v.compareTo(BigDecimal.ZERO) < 0 || v.compareTo(new BigDecimal("100")) > 0)
            return DEFAULT_MIN_MANNER;
        return v;
    }

    private BigDecimal positiveOrDefault(BigDecimal v, BigDecimal def) {
        return (v != null && v.compareTo(BigDecimal.ZERO) >= 0) ? v : def;
    }

    private String trimTo(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}