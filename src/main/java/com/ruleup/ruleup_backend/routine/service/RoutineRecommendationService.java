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
import com.ruleup.ruleup_backend.routine.match.RoutineMatchClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 1단계 추천 (제목 → 템플릿 매칭 → 지원 인증 방식 안내). 상태 저장 없음.
 *
 * 흐름:
 *   1) 입력 검증(제목/설명)
 *   2) LLM 으로 "후보 템플릿 중 하나 선택 + 목표값 추출"      ← LLM 책임은 여기까지
 *   3) 서버 검증: templateId 가 카탈로그에 실재하는가, 목표값이 범위 안인가(아니면 기본값으로)
 *   4) 인증 방식 안내:
 *        - 템플릿에 자동 옵션 있으면 → AUTO(기본) + MANUAL
 *        - 없으면                  → MANUAL 만
 *
 * ※ 추천 단계에서는 권한 보유 여부를 보지 않는다. AUTO 옵션엔 필요한 권한 "목록"만 실어주고,
 *   실제 보유 확인/요청은 생성(2단계, RoutineService)에서 한다.
 *   매칭 실패하거나 LLM 이 죽어도 항상 "수동 인증" 추천을 돌려준다(폴백).
 */
@Service
@RequiredArgsConstructor
public class RoutineRecommendationService {

    private final RoutineMatchClient matchClient;
    private final RoutineCatalog catalog;

    public RoutineRecommendationResponse recommend(RoutineRecommendationRequest req) {
        validateInput(req);

        // (2) LLM 매칭 — 한 번만 호출. 실패 시 RoutineMatch.none() 이 와서 자연히 수동 폴백된다.
        List<RoutineCandidate> candidates = catalog.candidates();
        RoutineMatch match = candidates.isEmpty()
                ? RoutineMatch.none()
                : matchClient.match(req.title(), req.description(), candidates);

        // (3) LLM 이 고른 templateId 가 서버 카탈로그에 실재할 때만 "매칭 성공"으로 인정.
        RoutineTemplate template = match.matched()
                ? catalog.findById(match.templateId()).orElse(null)
                : null;

        if (template == null) {
            return noMatchResponse(req.title());           // 직접 입력 = 수동만
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

    // ===== 매칭 실패(직접 입력): 수동 1개 =====
    private RoutineRecommendationResponse noMatchResponse(String title) {
        RoutineOption manual = new RoutineOption(
                "MANUAL", true, "MANUAL", SignalSource.PHOTO.name(), "NONE",
                null, List.of("CAMERA"));
        return new RoutineRecommendationResponse(
                false, null, title, null, "MANUAL", List.of(manual), List.of(), null);
    }

    // ===== 목표값: LLM 값이 범위 안이면 그 값, 아니면 템플릿 기본값(신뢰 경계 보정) =====
    private List<RoutineParam> resolveParams(RoutineTemplate template, RoutineMatch match) {
        List<RoutineParam> result = new ArrayList<>();
        for (ParamSpec spec : template.paramSpecs()) {
            Object value = spec.clampOrDefault(match.paramsOrEmpty().get(spec.key()));
            result.add(RoutineParam.of(spec, value));
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