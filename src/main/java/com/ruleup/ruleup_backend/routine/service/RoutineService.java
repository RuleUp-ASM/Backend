package com.ruleup.ruleup_backend.routine.service;

import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.routine.domain.ParamSpec;
import com.ruleup.ruleup_backend.routine.domain.RoutineTemplate;
import com.ruleup.ruleup_backend.routine.domain.SelectedMethod;
import com.ruleup.ruleup_backend.routine.domain.UserRoutine;
import com.ruleup.ruleup_backend.routine.dto.CreateRoutineRequest;
import com.ruleup.ruleup_backend.routine.dto.RoutineResponse;
import com.ruleup.ruleup_backend.routine.repository.UserRoutineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 2단계 생성 — 사용자가 인증 방식과 목표값을 확정한 최종값으로 루틴을 저장한다.
 *
 * 핵심은 "추천 응답을 그대로 믿지 않는다"는 것. 클라이언트가 보낸 templateId·method·params·권한을
 * 서버가 템플릿(진실) 기준으로 다시 검증한 뒤에만 저장한다(신뢰 경계).
 *
 * 검증 순서:
 *   1) 제목/설명 길이
 *   2) 인증 방식(AUTO/MANUAL) 파싱
 *   3) 템플릿 조회(있으면) — 없는 id 면 거부
 *   4) AUTO 면: 템플릿이 자동을 지원하는가 + 필요 권한을 실제로 보유했는가 재확인
 *   5) 목표값: 템플릿 스키마 기준으로 범위 검증(스키마에 없는 키는 무시)
 *   6) 인증 방식을 스냅샷으로 박아 저장
 *
 * 매칭 실패(직접 입력)면 templateId=null + MANUAL 만 허용하고 목표값은 비운다.
 */
@Service
@RequiredArgsConstructor
public class RoutineService {

    private final RoutineCatalog catalog;
    private final UserRoutineRepository userRoutineRepository;

    @Transactional
    public RoutineResponse create(UUID userId, CreateRoutineRequest req) {
        validateText(req);

        SelectedMethod method = parseMethod(req.selectedMethod());
        RoutineTemplate template = loadTemplate(req.templateId());   // null = 직접 입력

        UserRoutine routine = (method == SelectedMethod.AUTO)
                ? buildAuto(userId, req, template)
                : buildManual(userId, req, template);

        return RoutineResponse.from(userRoutineRepository.save(routine));
    }

    // ===== AUTO: 템플릿·권한 재검증 후 자동 스냅샷 =====
    private UserRoutine buildAuto(UUID userId, CreateRoutineRequest req, RoutineTemplate template) {
        if (template == null || !template.supportsAuto())
            throw new BusinessException(ErrorCode.ROUTINE_AUTO_NOT_SUPPORTED);

        List<String> granted = req.grantedPermissionsOrEmpty();
        if (!granted.containsAll(template.getAutoRequiredPermissions()))
            throw new BusinessException(ErrorCode.ROUTINE_PERMISSION_REQUIRED);

        Map<String, Object> params = validatedParams(template, req.paramsOrEmpty());
        return UserRoutine.auto(userId, req.title(), req.description(), template, params);
    }

    // ===== MANUAL: 템플릿 있으면 목표값 검증, 없으면(직접 입력) 빈 목표값 =====
    private UserRoutine buildManual(UUID userId, CreateRoutineRequest req, RoutineTemplate template) {
        Map<String, Object> params = (template != null)
                ? validatedParams(template, req.paramsOrEmpty())
                : Map.of();
        return UserRoutine.manual(userId, req.title(), req.description(), template, params);
    }

    /**
     * 목표값 검증: 템플릿 스키마의 각 파라미터에 대해
     *   - 사용자가 보낸 값이 있으면 범위 검증(틀리면 400)
     *   - 없으면 템플릿 기본값
     * 스키마에 없는 키는 조용히 무시한다(임의 값 주입 방지).
     */
    private Map<String, Object> validatedParams(RoutineTemplate template, Map<String, Object> userParams) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (ParamSpec spec : template.paramSpecs()) {
            Object raw = userParams.get(spec.key());
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

    private void validateText(CreateRoutineRequest req) {
        if (req == null || req.title() == null || req.title().isBlank())
            throw new BusinessException(ErrorCode.ROUTINE_TITLE_REQUIRED);
        if (req.title().length() > 100)
            throw new BusinessException(ErrorCode.ROUTINE_TITLE_TOO_LONG);
        if (req.description() != null && req.description().length() > 255)
            throw new BusinessException(ErrorCode.ROUTINE_DESCRIPTION_TOO_LONG);
    }
}