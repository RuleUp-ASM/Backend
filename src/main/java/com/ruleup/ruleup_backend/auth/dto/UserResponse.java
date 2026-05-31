package com.ruleup.ruleup_backend.auth.dto;

import com.ruleup.ruleup_backend.user.User;

import java.math.BigDecimal;
import java.util.List;

public record UserResponse(
        String id, String nickname, String email, String profileImageUrl,
        BigDecimal mannerTemperature, List<String> interestCategories) {

    public static UserResponse from(User user, BigDecimal mannerTemperature) {
        return new UserResponse(
                user.getId().toString(), user.getNickname(), user.getEmail(),
                user.getProfileImageUrl(), mannerTemperature, user.getInterestCategories());
    }
}