package com.ruleup.ruleup_backend.challenge.dto;

import java.util.Map;

/**
 * PATCH /api/v1/challenges/{challengeId} 200 응답.
 *  - moderation: 이번 수정으로 심사가 발생한 항목만(각 IN_REVIEW) — 없으면 null
 *  - updated: 실제 반영된 필드와 값
 */
public record PatchChallengeResponse(
        String challengeId,
        Map<String, String> moderation,
        Map<String, Object> updated
) {}
