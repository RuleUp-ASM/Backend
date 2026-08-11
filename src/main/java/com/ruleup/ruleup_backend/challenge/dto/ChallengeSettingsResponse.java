package com.ruleup.ruleup_backend.challenge.dto;

import com.ruleup.ruleup_backend.challenge.draft.DraftView;

import java.util.List;

/**
 * GET /api/v1/challenges/{challengeId}/settings 응답 — 방장 전용 설정 조회.
 *  - config: 입력 원본값(심사 대체 미적용 — 방장 본인 화면)
 *  - editableFields: 서버가 잠금 규칙으로 계산한 지금 수정 가능한 필드 목록(클라 판단은 참고용)
 *  - version: PATCH 에 그대로 되돌려 보내는 낙관 잠금 버전
 */
public record ChallengeSettingsResponse(
        Config config,
        List<String> editableFields,
        int version,
        Moderation moderation
) {
    public record Config(
            String title,
            String description,
            String imageUrl,
            String category,               // 수정 불가(불변)
            String mode,                   // SOLO / GROUP
            String visibility,             // 그룹 전용 — 솔로 null
            Boolean rankingVisible,        // 솔로 전용 — 그룹 null
            Integer capacity,
            String minTier,
            DraftView.Period period,
            List<String> repeatDays,
            int weeklyCount,
            List<DraftView.DraftParam> params,
            DraftView.Verification verification,
            Penalties penalties
    ) {}

    /** score·groupShare 는 서버 고정(표시용), watcher 만 수정 대상. */
    public record Penalties(boolean score, boolean groupShare, boolean watcher) {}

    public record Moderation(String title, String description, String image) {}
}
