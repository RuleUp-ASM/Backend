package com.ruleup.ruleup_backend.profile.dto;

import com.ruleup.ruleup_backend.user.domain.NicknamePolicy;
import com.ruleup.ruleup_backend.user.domain.User;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * GET/PATCH /profile/me 공통 응답. 시각은 ISO-8601 문자열.
 * nickname/profileImageUrl은 항상 "본인이 정한 값"(본인 화면이므로).
 * nicknameStatus/profileImageStatus로 타인에게 어떻게 보이는지(검수 상태)를 알려준다.
 *  - APPROVED: 타인에게도 본인 값 노출
 *  - PENDING : 검수 전/보류 → 타인에게는 임시 닉네임(tempNickname)·사진 숨김
 *  - REJECTED: 거절됨 → "바꿔주세요". 타인에게는 임시 닉네임·사진 숨김
 */
public record ProfileResponse(
        String id, String nickname, String email, String profileImageUrl,
        String nicknameStatus, String profileImageStatus, String tempNickname,
        String nicknameChangedAt, String nicknameChangeableAfter,
        BigDecimal mannerTemperature, List<String> interestCategories, String createdAt) {

    public static ProfileResponse from(User user, BigDecimal temp) {
        Instant changedAt = user.getNicknameChangedAt();
        String changeableAfter = (changedAt != null)
                ? changedAt.plus(NicknamePolicy.CHANGE_INTERVAL).toString() : null;
        return new ProfileResponse(
                user.getId().toString(), user.getNickname(), user.getEmail(), user.getProfileImageUrl(),
                user.getNicknameStatus().name(), user.getProfileImageStatus().name(), user.getTempNickname(),
                (changedAt != null ? changedAt.toString() : null),
                changeableAfter,
                temp, user.getInterestCategories(),
                (user.getCreatedAt() != null ? user.getCreatedAt().toString() : null));
    }
}