package com.ruleup.ruleup_backend.watcher.dto;

/**
 * 초대 생성 응답. 룰업이 직접 발송하지 않고, 생성자가 inviteUrl 을 본인 카톡으로 공유한다(§5.9).
 */
public record InvitationCreateResponse(
        String invitationId,
        String token,
        String inviteUrl,
        String status,
        String expiresAt,
        KakaoShare kakaoShare
) {
    public record KakaoShare(String title, String description, String buttonLabel) {}
}
