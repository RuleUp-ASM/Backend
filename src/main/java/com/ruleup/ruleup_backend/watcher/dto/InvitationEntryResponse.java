package com.ruleup.ruleup_backend.watcher.dto;

/**
 * 초대 링크 진입(공개) 응답. viewerIsUser 로 클라가 인앱 수락 vs 웹 동의 폼 분기.
 * 차단은 에러가 아니라 200 + blocked=true 로 안내(§11.4).
 */
public record InvitationEntryResponse(
        String invitationId,
        String status,
        String challengeTitle,
        String inviterNickname,
        boolean viewerIsUser,
        boolean blocked,
        String blockedUntil,
        String expiresAt
) {}
