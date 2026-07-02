package com.ruleup.ruleup_backend.challenge.service;

import com.ruleup.ruleup_backend.challenge.domain.Anonymity;
import com.ruleup.ruleup_backend.challenge.domain.ParticipationType;
import com.ruleup.ruleup_backend.challenge.domain.PenaltyConfig;
import com.ruleup.ruleup_backend.challenge.domain.RepeatDay;
import com.ruleup.ruleup_backend.challenge.domain.RewardConfig;
import com.ruleup.ruleup_backend.challenge.dto.ChallengeRecommendationResponse;
import com.ruleup.ruleup_backend.challenge.recommendation.ChallengeDraftClient;
import com.ruleup.ruleup_backend.challenge.recommendation.ChallengeDraftSuggestion;
import com.ruleup.ruleup_backend.challenge.recommendation.ChallengeSettings;
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
 *  - 루틴 매칭(인증·목표값·카테고리)과 챌린지 설정(참여방식·일정·패널티·보상)을
 *    LLM 한 번 호출({@link ChallengeDraftClient})로 함께 받는다(예전엔 직렬 두 번 → 지연 두 배였다).
 *  - 매칭 결과의 응답 구성(옵션·인증방식·목표값 검증)은 RoutineRecommendationService 가 그대로 맡는다.
 * 사용자는 이 초안을 클라에서 수정한 뒤 생성으로 보낸다(별도 수정 API 없음).
 *
 * LLM 값은 신뢰 경계 밖 → 서버가 전부 sanitize(유효하면 사용, 아니면 정적 기본값으로 폴백).
 * LLM 이 죽어도 기본값으로 항상 초안이 나온다.
 */
@Service
@RequiredArgsConstructor
public class ChallengeRecommendationService {

    private final RoutineRecommendationService routineRecommendationService;
    private final ChallengeDraftClient draftClient;

    // ===== 폴백 기본값 (LLM 실패/이상값일 때) =====
    private static final ParticipationType DEFAULT_PARTICIPATION = ParticipationType.SOLO;
    private static final List<String> DEFAULT_REPEAT_DAYS =
            List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");   // 매일
    private static final int DEFAULT_DURATION_DAYS = 14;
    private static final int START_OFFSET_DAYS = 1;                     // 내일 시작(서버 고정)
    private static final BigDecimal DEFAULT_MANNER_DEDUCTION = new BigDecimal("1.0");
    private static final BigDecimal DEFAULT_MANNER_GAIN = new BigDecimal("1.0");

    public ChallengeRecommendationResponse recommend(RoutineRecommendationRequest req) {
        // 입력 검증을 LLM 호출보다 먼저(잘못된 요청에 토큰/지연 낭비 안 하도록)
        routineRecommendationService.validate(req);

        // 루틴 매칭 + 챌린지 설정 — LLM 한 번만 호출(실패 시 empty → 전부 폴백)
        ChallengeDraftSuggestion draft = draftClient.suggest(
                req.title(), req.description(), routineRecommendationService.candidates());

        // 매칭분은 RoutineRecommendationService 가 카탈로그/스키마로 검증해 응답을 만든다(LLM 재호출 없음)
        RoutineRecommendationResponse routine =
                routineRecommendationService.recommendFromMatch(req, draft.matchOrNone());

        // LLM 이 제안하는 설정은 참여방식·반복요일뿐 → 신뢰 경계 밖이라 sanitize.
        // 기간·매너 점수는 LLM 이 추측할 근거가 없어 서버 정적 기본값으로 고정한다.
        ChallengeSettings s = draft.settingsOrEmpty();
        String participation = sanitizeParticipation(s.participationType());
        List<String> repeatDays = sanitizeRepeatDays(s.repeatDays());
        int duration = DEFAULT_DURATION_DAYS;
        BigDecimal deduction = DEFAULT_MANNER_DEDUCTION;
        BigDecimal gain = DEFAULT_MANNER_GAIN;

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