package com.ruleup.ruleup_backend.profile.dto;

public record PublicProfileResponse(String userId, String nickname, String profileImageUrl,
                                    String displayTier, long completedChallengeCount,
                                    boolean withdrawn, boolean blocked) {
}
