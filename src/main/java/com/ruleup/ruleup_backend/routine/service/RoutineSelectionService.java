package com.ruleup.ruleup_backend.routine.service;

import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.routine.domain.ParamSpec;
import com.ruleup.ruleup_backend.routine.domain.RoutineTemplate;
import com.ruleup.ruleup_backend.routine.domain.SelectedMethod;
import com.ruleup.ruleup_backend.routine.domain.VerificationConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 챌린지 생성 시 "사용자가 고른 루틴(템플릿+인증방식+목표값)"을 서버가 재검증해 스냅샷으로 확정한다.
 *
 * 추천 응답을 그대로 믿지 않는다(신뢰 경계): templateId·method·params 를 템플릿 기준으로 다시 본다.
 *   1) 인증 방식(AUTO/MANUAL) 파싱
 *   2) 템플릿 조회(있으면) — 없는 id 면 거부
 *   3) AUTO 면: 템플릿이 자동을 지원하는가 재확인
 *   4) 목표값: 템플릿 스키마 기준 범위 검증(스키마에 없는 키는 무시)
 *   5) 인증 방식 스냅샷(VerificationConfig) + 검증된 params 반환
 *
 * 권한은 여기서 보지 않는다. 필요 권한은 스냅샷(requiredPermissions)에 담겨 내려가고,
 * 실제 권한 grant·확인은 가입 후 최초 진입 셋업(§11.4 /setup)에서 한다.
 */
@Service
@RequiredArgsConstructor
public class RoutineSelectionService {

    private final RoutineCatalog catalog;

    /** 생성용: 선택값 전체 검증 → 챌린지에 박을 스냅샷 산출. */
    public ResolvedRoutine resolve(Long templateId, String selectedMethodRaw,
                                   Map<String, Object> rawParams) {
        SelectedMethod method = parseMethod(selectedMethodRaw);
        RoutineTemplate template = loadTemplate(templateId);   // null = 직접 입력

        if (method == SelectedMethod.AUTO) {
            if (template == null || !template.supportsAuto())
                throw new BusinessException(ErrorCode.ROUTINE_AUTO_NOT_SUPPORTED);

            // 권한 보유 확인은 여기서 하지 않는다. 필요 권한은 스냅샷에 담겨 내려가고
            // 가입 후 셋업(§11.4)에서 grant·검증한다.
            return new ResolvedRoutine(
                    template.getId(), VerificationConfig.auto(template), validatedParams(template, rawParams));
        }

        // MANUAL: 템플릿 있으면 목표값 검증, 없으면(직접 입력) 빈 목표값
        Long tid = (template != null) ? template.getId() : null;
        Map<String, Object> params = (template != null) ? validatedParams(template, rawParams) : Map.of();
        return new ResolvedRoutine(tid, VerificationConfig.manual(template), params);
    }

    /** 수정용: 인증 방식은 그대로 두고 목표값만 다시 검증(템플릿 스키마 기준). */
    public Map<String, Object> revalidateParams(Long templateId, Map<String, Object> rawParams) {
        RoutineTemplate template = loadTemplate(templateId);
        return (template != null) ? validatedParams(template, rawParams) : Map.of();
    }

    private Map<String, Object> validatedParams(RoutineTemplate template, Map<String, Object> rawParams) {
        Map<String, Object> source = (rawParams != null) ? rawParams : Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        for (ParamSpec spec : template.paramSpecs()) {
            Object raw = source.get(spec.key());
            try {
                result.put(spec.key(), (raw != null) ? spec.validate(raw) : spec.defaultValue());
            } catch (RuntimeException e) {
                throw new BusinessException(ErrorCode.INVALID_ROUTINE_PARAM);
            }
        }
        return result;
    }

    private RoutineTemplate loadTemplate(Long templateId) {
        if (templateId == null) return null;
        return catalog.findById(templateId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROUTINE_TEMPLATE_NOT_FOUND));
    }

    private SelectedMethod parseMethod(String raw) {
        if (raw == null) throw new BusinessException(ErrorCode.ROUTINE_METHOD_REQUIRED);
        try {
            return SelectedMethod.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.ROUTINE_METHOD_REQUIRED);
        }
    }
}