package com.ruleup.ruleup_backend.challenge.dto;

import java.util.List;

/**
 * POST /api/v1/challenges 201 응답.
 *  - moderation: 항목별 심사 상태 — 어느 상태든 모집 가능(기능 제한 없음).
 *  - verification.requiredPermissions: 생성 직후 클라가 요청할 OS 권한(이 위치가 유일).
 *  - personalSetupRequired: 개인 인증 설정(앵커·대상 앱) 진입 필요 여부.
 */
public record CreateChallengeResponse(
        String challengeId,
        String status,               // UPCOMING
        Moderation moderation,
        Verification verification,
        boolean personalSetupRequired,
        String createdAt
) {
    public record Moderation(String title, String description, String image) {}

    public record Verification(String type, String method, List<String> requiredPermissions) {}
}
