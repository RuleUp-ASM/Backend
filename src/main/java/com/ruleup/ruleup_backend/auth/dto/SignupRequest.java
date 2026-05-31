package com.ruleup.ruleup_backend.auth.dto;
import java.util.List;

public record SignupRequest(
        String signupToken, String nickname, List<String> interestCategories,
        String profileImageUrl, Agreements agreements) {

    /** terms·privacy 필수, marketing 선택 */
    public record Agreements(boolean terms, boolean privacy, boolean marketing) {}
}