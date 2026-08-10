package com.ruleup.ruleup_backend.report;

import java.util.List;

public final class ReportDtos {
    private ReportDtos() {}
    public record CreateRequest(String targetType, String targetUserId, String targetChallengeId,
                                String contextType, String reason, String detail) {}
    public record CreateResponse(String reportId, boolean duplicate, boolean blacklisted,
                                 String hiddenEffect) {}
    public record BlacklistResponse(List<UserItem> users, List<ChallengeItem> challenges) {}
    public record UserItem(String userId, String nickname, String profileImageUrl, String blockedAt) {}
    public record ChallengeItem(String challengeId, String title, String blockedAt) {}
    public record DeleteResponse(boolean removed) {}
}
