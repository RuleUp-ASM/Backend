package com.ruleup.ruleup_backend.routine.dto;

import java.util.List;
import java.util.Map;

/**
 * 2단계 생성 요청 — 사용자가 인증 방식과 목표값을 확정한 최종값.
 *  - templateId         : 추천에서 받은 템플릿 id(매칭 실패였으면 null)
 *  - selectedMethod     : AUTO / MANUAL (사용자가 고른 방식)
 *  - params             : 사용자가 수정한 목표값. 예: {"distance_km": 5}
 *  - grantedPermissions : AUTO 선택 시 권한 보유를 서버가 다시 확인하는 데 사용
 *
 * 서버는 이 값을 그대로 믿지 않고 재검증한다(템플릿 존재, AUTO 가능 여부, 권한, 목표값 범위).
 */
public record CreateRoutineRequest(
        String title,
        String description,
        Long templateId,
        String selectedMethod,
        Map<String, Object> params,
        List<String> grantedPermissions
) {
    public Map<String, Object> paramsOrEmpty() {
        return (params != null) ? params : Map.of();
    }

    public List<String> grantedPermissionsOrEmpty() {
        return (grantedPermissions != null) ? grantedPermissions : List.of();
    }
}