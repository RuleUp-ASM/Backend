package com.ruleup.ruleup_backend.routine.service;

import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.routine.domain.ParamSpec;
import com.ruleup.ruleup_backend.routine.domain.RoutineTemplate;
import com.ruleup.ruleup_backend.routine.domain.SignalSource;
import com.ruleup.ruleup_backend.routine.domain.WearableRequirement;
import com.ruleup.ruleup_backend.routine.dto.*;
import com.ruleup.ruleup_backend.routine.match.RoutineCandidate;
import com.ruleup.ruleup_backend.routine.match.RoutineMatch;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 1단계 추천 (제목 → 템플릿 매칭 → 지원 인증 방식 안내). 상태 저장 없음.
 *
 * 흐름:
 *   1) 입력 검증(제목/설명)
 *   2) LLM 으로 "후보 템플릿 중 하나 선택 + 목표값 추출"      ← LLM 책임은 여기까지
 *   3) 서버 검증: templateId 가 카탈로그에 실재하는가, 목표값이 범위 안인가(아니면 기본값으로)
 *   4) 인증 방식 안내:
 *        - 매칭됨(카탈로그는 자동 인증 가능 루틴만) → AUTO(기본) + MANUAL
 *        - 매칭 실패 = 자동 인증 불가            → 체크형(SELF_CHECK) 수동 1개만, AUTO 선택 불가
 *
 * ※ 카탈로그(RoutineTemplate)에는 자동 인증이 가능한 루틴만 있다(V18). 그래서 매칭되면 곧 "자동 인증 가능",
 *   매칭 실패는 "루틴을 못 찾음"이 아니라 "자동 인증이 불가능함"을 뜻하고 수동 체크형으로만 진행한다.
 * ※ 추천 단계에서는 권한 보유 여부를 보지 않는다. AUTO 옵션엔 필요한 권한 "목록"만 실어주고,
 *   실제 보유 확인/요청은 생성(2단계, RoutineService)에서 한다.
 *   매칭 실패하거나 LLM 이 죽어도 항상 "체크형 수동 인증" 추천을 돌려준다(폴백).
 */
@Service
@RequiredArgsConstructor
public class RoutineRecommendationService {

    private static final Logger log = LoggerFactory.getLogger(RoutineRecommendationService.class);

    private final RoutineCatalog catalog;

    /**
     * 이미 확보한 매칭 결과로 추천 응답을 만든다(LLM 재호출 없음).
     * 챌린지 추천이 통합 LLM 호출({@code ChallengeDraftClient})에서 받은 매칭 결과를 그대로 넘길 때 쓴다.
     */
    public RoutineRecommendationResponse recommendFromMatch(RoutineRecommendationRequest req, RoutineMatch match) {
        validateInput(req);
        return buildFromMatch(req, (match != null) ? match : RoutineMatch.none());
    }

    /** LLM 에 줄 후보 템플릿 목록(통합 호출 측에서 프롬프트에 싣는다). */
    public List<RoutineCandidate> candidates() {
        return catalog.candidates();
    }

    /** 요청 검증만 분리 공개(통합 호출 측이 LLM 호출 전에 먼저 막을 수 있게). */
    public void validate(RoutineRecommendationRequest req) {
        validateInput(req);
    }

    // (3) LLM 이 고른 templateId 가 서버 카탈로그에 실재할 때만 "매칭 성공"으로 인정.
    private RoutineRecommendationResponse buildFromMatch(RoutineRecommendationRequest req, RoutineMatch match) {
        if (!match.matched()) {
            return noMatchResponse(req.title());           // LLM 이 매칭 못 함 = 직접 입력(수동만) — 정상 폴백
        }
        RoutineTemplate template = catalog.findById(match.templateId()).orElse(null);
        if (template == null) {
            // LLM 이 후보에 없는 id 를 냈다(hallucination) — 출력 형식 실패로 집계.
            log.info("llm_fail class=VALIDATION reason=unknown_template templateId={}", match.templateId());
            return noMatchResponse(req.title());
        }
        return matchedResponse(req, template, match);      // 자동/수동 안내
    }

    // ===== 매칭 성공: 목표값 보정 + 인증 방식 안내(권한 안 봄) =====
    private RoutineRecommendationResponse matchedResponse(RoutineRecommendationRequest req,
                                                          RoutineTemplate template,
                                                          RoutineMatch match) {
        List<RoutineParam> params = resolveParams(template, match);

        List<RoutineOption> options = new ArrayList<>();
        String recommendedMethod;

        if (template.supportsAuto()) {
            options.add(autoOption(template));            // ① 자동(기본)
            options.add(manualOption(template, false));   // ② 수동
            recommendedMethod = "AUTO";
        } else {
            options.add(manualOption(template, true));    // 자동 불가 → 수동만
            recommendedMethod = "MANUAL";
        }

        return new RoutineRecommendationResponse(
                true, template.getId(), req.title(), template.getCategory().name(),
                recommendedMethod, options, params, template.getRationale());
    }

    // ===== 매칭 실패 = 자동 인증 불가 → 체크형(수동) 1개만, AUTO 선택 불가 =====
    // "루틴을 못 찾음"이 아니라 "자동 인증이 가능한 루틴이 아님"이 사용자 메시지의 핵심.
    private RoutineRecommendationResponse noMatchResponse(String title) {
        RoutineOption selfCheck = new RoutineOption(
                "MANUAL", true, "MANUAL", SignalSource.SELF_CHECK.name(), "NONE",
                null, List.of());   // 체크형 = 사진/권한 없이 직접 체크
        return new RoutineRecommendationResponse(
                false, null, title, null, "MANUAL", List.of(selfCheck), List.of(),
                "자동 인증이 가능한 루틴이 아니에요. 직접 체크(수동 인증)로만 진행할 수 있어요.");
    }

    // ===== 목표값: LLM 값이 범위 안이면 그 값, 아니면 템플릿 기본값(신뢰 경계 보정) =====
    // 탈락 사유(존재하지 않는 키·타입/범위 불일치)는 llm_fail class=VALIDATION 으로 남겨 집계한다.
    private List<RoutineParam> resolveParams(RoutineTemplate template, RoutineMatch match) {
        Map<String, Object> provided = match.paramsOrEmpty();
        List<RoutineParam> result = new ArrayList<>();
        List<String> known = new ArrayList<>();
        for (ParamSpec spec : template.paramSpecs()) {
            known.add(spec.key());
            Object raw = provided.get(spec.key());
            if (raw != null) {
                try {
                    spec.validate(raw);   // 통과 여부만 확인(값은 아래 clampOrDefault 결과 사용)
                } catch (RuntimeException e) {
                    log.info("llm_fail class=VALIDATION reason=param_value template={} key={} raw={}",
                            template.getId(), spec.key(), raw);
                }
            }
            result.add(RoutineParam.of(spec, spec.clampOrDefault(raw)));
        }
        for (String key : provided.keySet()) {
            if (!known.contains(key)) {
                log.info("llm_fail class=VALIDATION reason=unknown_key template={} key={}", template.getId(), key);
            }
        }
        return result;
    }

    // ===== 인증 옵션 빌더 =====
    private RoutineOption autoOption(RoutineTemplate t) {
        WearableRequirement wear = (t.getAutoWearableReq() != null)
                ? t.getAutoWearableReq() : WearableRequirement.NONE;
        return new RoutineOption(
                "AUTO", true,
                t.getAutoVerificationType().name(),
                t.getAutoSignalSource().name(),
                wear.name(),
                t.getAutoExternalService(),
                t.getAutoRequiredPermissions());   // 보유 여부 안 따지고 "필요 목록"만 전달
    }

    private RoutineOption manualOption(RoutineTemplate t, boolean recommended) {
        SignalSource signal = t.getManualSignalSource();
        List<String> perms = (signal == SignalSource.PHOTO) ? List.of("CAMERA") : List.of();
        return new RoutineOption(
                "MANUAL", recommended,
                "MANUAL", signal.name(), "NONE", null, perms);
    }

    // ===== 입력 검증 =====
    private void validateInput(RoutineRecommendationRequest req) {
        if (req == null || req.title() == null || req.title().isBlank())
            throw new BusinessException(ErrorCode.ROUTINE_TITLE_REQUIRED);
        if (req.title().length() > 100)
            throw new BusinessException(ErrorCode.ROUTINE_TITLE_TOO_LONG);
        if (req.description() != null && req.description().length() > 255)
            throw new BusinessException(ErrorCode.ROUTINE_DESCRIPTION_TOO_LONG);
    }

    /** 추천 선택 경로: templateId로 루틴 상세를 바로 구성(LLM 매칭 우회). 목표값은 템플릿 기본값. */
    public RoutineRecommendationResponse recommendByTemplate(Long templateId, String title) {
        if (templateId == null) return noMatchResponse(title);
        RoutineTemplate template = catalog.findById(templateId).orElse(null);
        if (template == null) return noMatchResponse(title);
        String t = (title != null && !title.isBlank()) ? title : template.getName();
        return matchedResponse(new RoutineRecommendationRequest(t, null), template, RoutineMatch.none());
    }
}