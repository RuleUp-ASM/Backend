package com.ruleup.ruleup_backend.profile.dto;
import java.util.List;

/** 부분 수정: null인 필드는 변경하지 않음. */
public record UpdateProfileRequest(String nickname, List<String> interestCategories, String profileImageUrl) {}