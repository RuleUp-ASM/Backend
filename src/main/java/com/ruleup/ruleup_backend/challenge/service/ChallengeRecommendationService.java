package com.ruleup.ruleup_backend.challenge.service;

import com.ruleup.ruleup_backend.challenge.domain.Anonymity;
import com.ruleup.ruleup_backend.challenge.domain.ParticipationType;
import com.ruleup.ruleup_backend.challenge.domain.PenaltyConfig;
import com.ruleup.ruleup_backend.challenge.domain.RepeatDay;
import com.ruleup.ruleup_backend.challenge.domain.RewardConfig;
import com.ruleup.ruleup_backend.challenge.dto.ChallengeRecommendationResponse;
import com.ruleup.ruleup_backend.challenge.recommendation.ChallengeSettings;
import com.ruleup.ruleup_backend.challenge.recommendation.ChallengeSettingsClient;
import com.ruleup.ruleup_backend.routine.dto.RoutineRecommendationRequest;
import com.ruleup.ruleup_backend.routine.dto.RoutineRecommendationResponse;
import com.ruleup.ruleup_backend.routine.service.RoutineRecommendationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 챌린지 추천 = "전체 초안 만들기".
 *  - 인증·목표값(카테고리 포함)은 루틴 매칭(RoutineRecommendationService)에서,
 *  - 참여방식·일정·패널티·보상·익명은 LLM(ChallengeSettingsClient)이 제목 보고 제안한다.
 * 사용자는 이 초안을 클라에서 수정한 뒤 생성으로 보낸다(별도 수정 API 없음).
 *
 * LLM 값은 신뢰 경계 밖 → 서버가 전부 sanitize(유효하면 사용, 아니면 정적 기본값으로 폴백).
 * LLM 이 죽어도 기본값으로 항상 초안이 나온다.
 */
@Service
@RequiredArgsConstructor
public class ChallengeRecommendationService {

    private final RoutineRecommendationService routineRecommendationService;
    private final ChallengeSettingsClient settingsClient;

    // ===== 폴백 기본값 (LLM 실패/이상값일 때) =====
    private static final ParticipationType DEFAULT_PARTICIPATION = ParticipationType.SOLO;
    private static final List<String> DEFAULT_REPEAT_DAYS =
            List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");   // 매일
    private static final int DEFAULT_DURATION_DAYS = 14;
    private static final int START_OFFSET_DAYS = 1;                     // 내일 시작(서버 고정)
    private static final BigDecimal DEFAULT_MANNER_DEDUCTION = new BigDecimal("1.0");
    private static final BigDecimal DEFAULT_MANNER_GAIN = new BigDecimal("1.0");

    public ChallengeRecommendationResponse recommend(RoutineRecommendationRequest req) {
        // 1) 루틴 매칭(인증·목표값) — LLM 호출 1
        RoutineRecommendationResponse routine = routineRecommendationService.recommend(req);

        // 2) 챌린지 설정 제안 — LLM 호출 2 (실패 시 empty → 전부 폴백)
        ChallengeSettings s = settingsClient.suggest(req.title(), req.description());

        String participation = sanitizeParticipation(s.participationType());
        List<String> repeatDays = sanitizeRepeatDays(s.repeatDays());
        int duration = sanitizeDuration(s.durationDays());
        BigDecimal deduction = sanitizeNonNegative(s.mannerDeduction(), DEFAULT_MANNER_DEDUCTION);
        BigDecimal gain = sanitizeNonNegative(s.mannerGain(), DEFAULT_MANNER_GAIN);

        LocalDate start = LocalDate.now().plusDays(START_OFFSET_DAYS);
        LocalDate end = start.plusDays((long) duration - 1);

        return new ChallengeRecommendationResponse(
                routine.matched(),
                routine.templateId(),
                routine.title(),
                (req != null) ? req.description() : null,
                routine.category(),
                routine.recommendedMethod(),
                routine.options(),
                routine.params(),
                routine.rationale(),
                participation,
                null,                       // minMannerTemperature: 그룹 선택 시 클라에서 설정
                repeatDays,
                duration,
                start.toString(),
                end.toString(),
                new PenaltyConfig(deduction, new PenaltyConfig.SnsShare(false, null), false),
                new RewardConfig(gain),
                Anonymity.REAL.name());        // 초안 기본은 실명(§11.2 — 응답 누락 금지)
    }

    // ===== LLM 값 검증·폴백 (신뢰 경계) =====

    private String sanitizeParticipation(String v) {
        if (v == null) return DEFAULT_PARTICIPATION.name();
        try {
            return ParticipationType.valueOf(v.trim().toUpperCase()).name();
        } catch (IllegalArgumentException e) {
            return DEFAULT_PARTICIPATION.name();
        }
    }

    private List<String> sanitizeRepeatDays(List<String> days) {
        if (days == null || days.isEmpty()) return DEFAULT_REPEAT_DAYS;
        List<String> upper = days.stream().filter(d -> d != null).map(d -> d.trim().toUpperCase()).toList();
        return (RepeatDay.allValid(upper) && !upper.isEmpty()) ? upper : DEFAULT_REPEAT_DAYS;
    }

    private int sanitizeDuration(Integer d) {
        return (d != null && d >= 1) ? d : DEFAULT_DURATION_DAYS;
    }

    private BigDecimal sanitizeNonNegative(BigDecimal v, BigDecimal fallback) {
        return (v != null && v.compareTo(BigDecimal.ZERO) >= 0) ? v : fallback;
    }

    /** 추천 선택 경로(Path A): templateId로 챌린지 초안 구성. LLM(루틴매칭·설정제안) 둘 다 우회 — 설정은 정적 기본값. */
    public ChallengeRecommendationResponse recommendByTemplate(Long templateId, String title, String description) {
        RoutineRecommendationResponse routine = routineRecommendationService.recommendByTemplate(templateId, title);
        LocalDate start = LocalDate.now().plusDays(START_OFFSET_DAYS);
        LocalDate end = start.plusDays((long) DEFAULT_DURATION_DAYS - 1);
        return new ChallengeRecommendationResponse(
                routine.matched(), routine.templateId(), routine.title(), description, routine.category(),
                routine.recommendedMethod(), routine.options(), routine.params(), routine.rationale(),
                DEFAULT_PARTICIPATION.name(), null, DEFAULT_REPEAT_DAYS, DEFAULT_DURATION_DAYS,
                start.toString(), end.toString(),
                new PenaltyConfig(DEFAULT_MANNER_DEDUCTION, new PenaltyConfig.SnsShare(false, null), false),
                new RewardConfig(DEFAULT_MANNER_GAIN),
                Anonymity.REAL.name());
    }
}