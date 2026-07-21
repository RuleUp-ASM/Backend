package com.ruleup.ruleup_backend.me.dto;

import java.util.List;

/** 친구 초대(GET /me/invitation): 내 초대 코드/링크 + 초대 현황. */
public record MeInvitationResponse(
        String inviteCode,
        String inviteUrl,
        String rewardDescription,
        List<Invitee> invitees
) {
    /** nickname은 visibleNicknameTo 적용(검수 전 = tempNickname). status는 항상 SIGNED_UP. */
    public record Invitee(String nickname, String status, String occurredAt) {}
}
