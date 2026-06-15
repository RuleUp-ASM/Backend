package com.ruleup.ruleup_backend.routine.dto;

import com.ruleup.ruleup_backend.routine.domain.UserRoutine;

import java.util.List;
import java.util.Map;

/** 2단계 생성 응답 — 저장된 사용자 루틴. */
public record RoutineResponse(
        Long routineId,
        String title,
        String description,
        Long templateId,
        String selectedMethod,
        String verificationType,
        String signalSource,
        String wearableRequirement,
        String externalService,
        List<String> requiredPermissions,
        Map<String, Object> params
) {
    public static RoutineResponse from(UserRoutine r) {
        return new RoutineResponse(
                r.getId(),
                r.getTitle(),
                r.getDescription(),
                r.getTemplateId(),
                r.getSelectedMethod().name(),
                r.getVerificationType().name(),
                r.getSignalSource().name(),
                r.getWearableReq().name(),
                r.getExternalService(),
                r.getRequiredPermissions(),
                r.getParams());
    }
}