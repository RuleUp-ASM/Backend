package com.ruleup.ruleup_backend.routine.service;

import com.ruleup.ruleup_backend.routine.domain.VerificationConfig;

import java.util.Map;

/**
 * 사용자가 고른 루틴을 서버가 검증·확정한 결과. 챌린지가 이걸 그대로 박아 저장한다.
 *  - templateId   : 매칭된 템플릿(직접 입력이면 null)
 *  - verification : 인증 방식 스냅샷(템플릿에서 떠온 값)
 *  - params       : 검증 통과한 목표값(예: {"distance_km": 5})
 */
public record ResolvedRoutine(
        Long templateId,
        VerificationConfig verification,
        Map<String, Object> params
) {
}