package com.ruleup.ruleup_backend.auth.dto;

import com.ruleup.ruleup_backend.user.domain.User;

import java.math.BigDecimal;
import java.util.List;

public record UserResponse(
        String id, String nickname, String nicknameStatus, String email, String profileImageUrl,
        BigDecimal mannerTemperature, List<String> interestCategories) {

    /** 로그인 응답용 — 계약상 로그인 user에는 nicknameStatus가 없으므로 null. */
    public static UserResponse from(User user, BigDecimal mannerTemperature) {
        return build(user, null, mannerTemperature);
    }

    /** 가입 응답용 — 계약상 signup user는 nicknameStatus(PENDING)를 포함한다. */
    public static UserResponse fromSignup(User user, BigDecimal mannerTemperature) {
        return build(user, user.getNicknameStatus().name(), mannerTemperature);
    }

    private static UserResponse build(User user, String nicknameStatus, BigDecimal mannerTemperature) {
        return new UserResponse(
                user.getId().toString(), user.getNickname(), nicknameStatus, user.getEmail(),
                user.getProfileImageUrl(), mannerTemperature, user.getInterestCategories());
    }
}