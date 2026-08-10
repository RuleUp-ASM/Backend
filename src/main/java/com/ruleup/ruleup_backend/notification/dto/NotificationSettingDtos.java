package com.ruleup.ruleup_backend.notification.dto;

import java.util.List;

public final class NotificationSettingDtos {
    private NotificationSettingDtos() {}
    public record PatchRequest(Boolean challengeActivity, Boolean roomActivity, Boolean tierActivity,
                               Boolean marketing, Boolean nightPush, List<String> mutedChallengeIds) {}
    public record Response(boolean challengeActivity, boolean roomActivity, boolean tierActivity,
                           boolean marketing, boolean nightPush, List<String> mutedChallengeIds) {}
}
