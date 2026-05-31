package com.ruleup.ruleup_backend.profile.dto;

import com.ruleup.ruleup_backend.user.NicknamePolicy;
import com.ruleup.ruleup_backend.user.User;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** GET/PATCH /profile/me 공통 응답. 시각은 ISO-8601 문자열. */
public record ProfileResponse(
        String id, String nickname, String email, String profileImageUrl,
        String nicknameChangedAt, String nicknameChangeableAfter,
        BigDecimal mannerTemperature, List<String> interestCategories, String createdAt) {

    public static ProfileResponse from(User user, BigDecimal temp) {
        Instant changedAt = user.getNicknameChangedAt();
        String changeableAfter = (changedAt != null)
                ? changedAt.plus(NicknamePolicy.CHANGE_INTERVAL).toString() : null;
        return new ProfileResponse(
                user.getId().toString(), user.getNickname(), user.getEmail(), user.getProfileImageUrl(),
                (changedAt != null ? changedAt.toString() : null),
                changeableAfter,
                temp, user.getInterestCategories(),
                (user.getCreatedAt() != null ? user.getCreatedAt().toString() : null));
    }
}