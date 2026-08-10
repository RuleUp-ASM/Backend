package com.ruleup.ruleup_backend.challenge.dto;

import java.util.List;

/** GET /challenges/{id}/members API 문서 계약. */
public record MemberListResponse(String ownerType, List<Member> members) {
    public record Member(String userId, String nickname, String profileImageUrl,
                         String role, String displayTier, String joinedAt, boolean blocked) {}
}
