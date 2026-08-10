package com.ruleup.ruleup_backend.challenge.draft;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeStatus;
import com.ruleup.ruleup_backend.challenge.domain.MemberStatus;
import com.ruleup.ruleup_backend.challenge.domain.ParticipationType;
import com.ruleup.ruleup_backend.challenge.domain.RepeatDay;
import com.ruleup.ruleup_backend.challenge.dto.CreationRecommendationsResponse;
import com.ruleup.ruleup_backend.challenge.dto.DraftResponse;
import com.ruleup.ruleup_backend.challenge.dto.TemplateDraftResponse;
import com.ruleup.ruleup_backend.challenge.recommendation.ChallengeDraftClient;
import com.ruleup.ruleup_backend.challenge.recommendation.ChallengeDraftSuggestion;
import com.ruleup.ruleup_backend.challenge.recommendation.ChallengeSettings;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.recommendation.dto.RecommendedRoutine;
import com.ruleup.ruleup_backend.recommendation.service.RecommendationService;
import com.ruleup.ruleup_backend.routine.domain.ParamSpec;
import com.ruleup.ruleup_backend.routine.domain.RoutineTemplate;
import com.ruleup.ruleup_backend.routine.match.RoutineMatch;
import com.ruleup.ruleup_backend.routine.service.RoutineCatalog;
import com.ruleup.ruleup_backend.score.domain.Tier;
import com.ruleup.ruleup_backend.score.UserScoreSummaryRepository;
import com.ruleup.ruleup_backend.user.domain.InterestCategory;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 챌린지 생성 진입 초안 파이프라인(경로 A·B) + 추천 3개 보장.
 *
 *  - 경로 B(draft): 설명 1~200자 → LLM 5-Step → 초안 + draftId(24h 보관, origin=AI).
 *    Step 1·2 차단은 에러가 아닌 result=FALLBACK. LLM 장애·파싱 실패는 기본 템플릿 대체.
 *  - 경로 A(by-template): 템플릿 기본값 초안 즉시 구성(LLM 미경유) + draftId(origin=TEMPLATE).
 *  - 추천 3개: 어떤 경우에도 3개 보장. 진행 중 카테고리 제외(기타 예외)하되 3개 보장이 우선.
 *
 * LLM 값은 신뢰 경계 밖 → 서버가 전부 sanitize(유효하면 사용, 아니면 기본값 폴백).
 */
@Service
@RequiredArgsConstructor
public class ChallengeDraftService {

    private static final Logger log = LoggerFactory.getLogger(ChallengeDraftService.class);

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int DESCRIPTION_MAX = 200;
    private static final int TITLE_MAX = 30;
    private static final int DEFAULT_CAPACITY = 50;
    private static final int START_OFFSET_DAYS = 1;      // 시작일 = 생성일 + 1일
    private static final int PERIOD_DAYS = 14;           // 종료일 = 시작일 + 2주
    private static final List<String> ALL_DAYS =
            List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");
    private static final String FALLBACK_MESSAGE = "AI가 파악하지 못한 루틴이에요. 더 구체적으로 적어주세요.";
    /** LLM 장애 — 사용자 입력에는 문제가 없으므로 재입력이 아니라 재시도를 안내한다. */
    private static final String LLM_FAILURE_MESSAGE =
            "지금은 추천을 만들 수 없어요. 잠시 후 다시 시도하거나 추천 루틴에서 골라주세요.";

    private final ChallengeDraftClient draftClient;
    private final RoutineCatalog catalog;
    private final ChallengeDraftRepository draftRepository;
    private final UserScoreSummaryRepository scoreSummaryRepository;
    private final RecommendationService recommendationService;
    private final ChallengeMemberRepository memberRepository;
    private final ChallengeRepository challengeRepository;

    // ===== 경로 B: 설명 입력(LLM 5-Step) =====

    @Transactional
    public DraftResponse draft(UUID userId, String description) {
        validateDescription(description);

        // Step 1~4 — LLM 한 번 호출.
        // 차단(Step1·2)은 물론 LLM 장애·파싱 실패도 FALLBACK 이다. 장애 응답으로 초안을 만들면
        // 사용자 원문이 그대로 AI 제목·설명이 되고, 생성 시 원본 대조에서 "AI가 쓴 그대로"로 보여
        // 심사 면제(EXEMPT) 로 무심사 노출된다 — AI 면제의 신뢰 원본은 Step1·2를 통과한 결과뿐이다.
        ChallengeDraftSuggestion suggestion = draftClient.suggest(description, catalog.candidates());
        if (suggestion.unusable()) {
            log.info("draft_result success=false fallback_step={} userId={}",
                    suggestion.blocked() ? "STEP1_2" : "LLM_FAILURE", userId);
            return DraftResponse.fallback(suggestion.blocked() ? FALLBACK_MESSAGE : LLM_FAILURE_MESSAGE);
        }

        // Step 3 — 루틴 테이블 매칭(카탈로그 실재 검증)
        RoutineMatch match = suggestion.matchOrNone();
        RoutineTemplate template = match.matched()
                ? catalog.findById(match.templateId()).orElse(null) : null;
        if (match.matched() && template == null) {
            log.info("llm_fail class=VALIDATION reason=unknown_template templateId={}", match.templateId());
        }

        // Step 5 — 형식 검증·이상값 보정(신뢰 경계): 제목·설명·카테고리·설정 전부 sanitize
        ChallengeSettings s = suggestion.settingsOrEmpty();
        String title = sanitizeTitle(s.title(), description);
        String correctedDescription = sanitizeDescription(s.description(), description);
        String category = (template != null)
                ? template.getCategory().name()
                : sanitizeCategory(s.category());
        String mode = sanitizeMode(s.participationType());
        List<String> repeatDays = sanitizeRepeatDays(s.repeatDays());

        DraftView view = assemble(userId, title, correctedDescription, category, mode,
                template, match.paramsOrEmpty());

        ChallengeDraft saved = draftRepository.save(ChallengeDraft.of(
                userId, ChallengeDraft.Origin.AI,
                (template != null) ? template.getId() : null, null,
                view, repeatDays, Instant.now()));
        log.info("draft_result success=true origin=AI draftId={} templateId={}",
                saved.getId(), saved.getTemplateId());
        return DraftResponse.ok(saved.getId().toString(), view);
    }

    // ===== 경로 A: 추천 루틴 선택(LLM 미경유) =====

    @Transactional
    public TemplateDraftResponse byTemplate(UUID userId, Long templateId) {
        if (templateId == null) throw new BusinessException(ErrorCode.TEMPLATE_ID_REQUIRED);
        RoutineTemplate template = catalog.findById(templateId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TEMPLATE_NOT_FOUND));

        DraftView view = assemble(userId, truncate(template.getName(), TITLE_MAX),
                template.getDescription(), template.getCategory().name(),
                ParticipationType.SOLO.name(), template, Map.of());

        ChallengeDraft saved = draftRepository.save(ChallengeDraft.of(
                userId, ChallengeDraft.Origin.TEMPLATE, template.getId(), null,
                view, ALL_DAYS, Instant.now()));
        return new TemplateDraftResponse(saved.getId().toString(), view);
    }

    // ===== 추천 3개 상시 보장 =====

    @Transactional(readOnly = true)
    public CreationRecommendationsResponse recommendations(UUID userId) {
        // 진행 중(종료 전) 챌린지의 카테고리 — 기타(ETC)는 제외 예외
        Set<String> activeCategories = activeCategories(userId);

        List<CreationRecommendationsResponse.Item> items = new ArrayList<>();
        Set<Long> used = new HashSet<>();

        // 1순위: 세그먼트·관심사 랭킹(진행 중 템플릿 제외 내장) 중 진행 중 카테고리가 아닌 것
        for (RecommendedRoutine r : recommendationService.recommendRoutines(userId, 3)) {
            if (items.size() >= 3) break;
            if (activeCategories.contains(r.category())) continue;
            if (used.add(r.templateId())) items.add(toItem(r.templateId(), displayReason(r.reason())));
        }
        // 2순위: 카탈로그 전체에서 진행 중 카테고리를 피해 채움
        fill(items, used, activeCategories);
        // 3순위: 그래도 부족하면 제외를 풀어서라도 3개 보장(스펙 — 3개 보장이 우선)
        fill(items, used, Set.of());

        return new CreationRecommendationsResponse(items);
    }

    private void fill(List<CreationRecommendationsResponse.Item> items, Set<Long> used,
                      Set<String> excludedCategories) {
        if (items.size() >= 3) return;
        for (var candidate : catalog.candidates()) {
            if (items.size() >= 3) break;
            if (excludedCategories.contains(candidate.category())) continue;
            if (used.add(candidate.id())) items.add(toItem(candidate.id(), "전체 인기"));
        }
    }

    private CreationRecommendationsResponse.Item toItem(Long templateId, String reason) {
        RoutineTemplate t = catalog.findById(templateId).orElseThrow();
        return new CreationRecommendationsResponse.Item(
                t.getId(), truncate(t.getName(), TITLE_MAX), t.getDescription(),
                t.getCategory().name(), "AUTO", reason);
    }

    private Set<String> activeCategories(UUID userId) {
        Set<String> categories = new HashSet<>();
        for (ChallengeMember m : memberRepository.findByUserIdAndStatus(userId, MemberStatus.ACTIVE)) {
            challengeRepository.findById(m.getChallengeId()).ifPresent(c -> {
                if (c.getStatus() != ChallengeStatus.COMPLETED
                        && !InterestCategory.ETC.name().equals(c.getCategory())) {
                    categories.add(c.getCategory());
                }
            });
        }
        return categories;
    }

    private String displayReason(String reason) {
        return switch (reason) {
            case "POPULAR_IN_SEGMENT" -> "또래 인기 루틴";
            case "INTEREST_MATCH" -> "관심사 맞춤";
            default -> "전체 인기";
        };
    }

    // ===== 초안 조립(경로 공통) =====

    private DraftView assemble(UUID userId, String title, String description, String category,
                               String mode, RoutineTemplate template, Map<String, Object> llmParams) {
        boolean group = ParticipationType.GROUP.name().equals(mode);
        boolean auto = template != null && template.supportsAuto();

        // 서버는 UTC로 도는데 챌린지 날짜 축은 전부 KST다. 타임존을 빼면 UTC 15시~24시
        // (KST 자정~오전 9시)에 시작일이 "KST 오늘"이 되어 생성일+1 규칙이 깨진다.
        LocalDate start = LocalDate.now(KST).plusDays(START_OFFSET_DAYS);
        LocalDate end = start.plusDays(PERIOD_DAYS);

        DraftView.Verification verification = auto
                ? new DraftView.Verification("AUTO", template.getVerificationMethod(),
                        template.getAutoRequiredPermissions())
                : new DraftView.Verification("MANUAL", "SELF_CHECK", List.of());

        return new DraftView(
                title, description, category, mode,
                group ? "PUBLIC" : null,
                group ? null : Boolean.TRUE,
                DEFAULT_CAPACITY,
                displayTier(userId).name(),
                new DraftView.Period(start.toString(), end.toString()),
                buildParams(template, llmParams),
                verification,
                // 점수 패널티 = 자동 방 ON 고정 / 그룹 공유 = 그룹 ON 고정 / 감시자 기본 off
                new DraftView.Penalties(auto, group, false));
    }

    /** 목표값: 템플릿 스펙 순서대로, LLM 값이 유효하면 그 값·아니면 기본값(문자열 표기). */
    private List<DraftView.DraftParam> buildParams(RoutineTemplate template, Map<String, Object> provided) {
        if (template == null) return List.of();
        List<DraftView.DraftParam> params = new ArrayList<>();
        Map<String, Object> given = (provided != null) ? provided : Map.of();
        for (ParamSpec spec : template.paramSpecs()) {
            Object value = spec.clampOrDefault(given.get(spec.key()));
            params.add(new DraftView.DraftParam(
                    spec.key(), asString(value), asString(spec.defaultValue()),
                    spec.kind().name(), spec.unit(), spec.min(), spec.max()));
        }
        return params;
    }

    /** 생성자 표시 티어. 요약 행이 없거나 UNRANKED 면 BRONZE(가입 초기 티어). */
    private Tier displayTier(UUID userId) {
        Tier tier = scoreSummaryRepository.findById(userId)
                .map(s -> s.getDisplayTier()).orElse(Tier.BRONZE);
        return (tier == Tier.UNRANKED) ? Tier.BRONZE : tier;
    }

    // ===== sanitize (신뢰 경계) =====

    private void validateDescription(String description) {
        if (description == null || description.isBlank())
            throw new BusinessException(ErrorCode.ROUTINE_DESCRIPTION_REQUIRED);
        if (description.length() > DESCRIPTION_MAX)
            throw new BusinessException(ErrorCode.ROUTINE_DESCRIPTION_TOO_LONG);
    }

    private String sanitizeTitle(String title, String description) {
        String t = (title != null && !title.isBlank()) ? title.trim() : description.trim();
        return truncate(t, TITLE_MAX);
    }

    private String sanitizeDescription(String corrected, String original) {
        String d = (corrected != null && !corrected.isBlank()) ? corrected.trim() : original.trim();
        return truncate(d, DESCRIPTION_MAX);
    }

    private String sanitizeCategory(String category) {
        if (category != null && InterestCategory.allValid(List.of(category.trim().toUpperCase()))) {
            return category.trim().toUpperCase();
        }
        return InterestCategory.ETC.name();
    }

    private String sanitizeMode(String mode) {
        if (mode == null) return ParticipationType.SOLO.name();
        try {
            return ParticipationType.valueOf(mode.trim().toUpperCase()).name();
        } catch (IllegalArgumentException e) {
            return ParticipationType.SOLO.name();
        }
    }

    private List<String> sanitizeRepeatDays(List<String> days) {
        if (days == null || days.isEmpty()) return ALL_DAYS;
        List<String> upper = days.stream().filter(d -> d != null)
                .map(d -> d.trim().toUpperCase()).toList();
        return (!upper.isEmpty() && RepeatDay.allValid(upper)) ? upper : ALL_DAYS;
    }

    private static String asString(Object v) {
        return (v == null) ? null : String.valueOf(v);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return (s.length() <= max) ? s : s.substring(0, max);
    }
}
