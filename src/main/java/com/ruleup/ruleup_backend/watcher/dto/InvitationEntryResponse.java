package com.ruleup.ruleup_backend.watcher.dto;
public record InvitationEntryResponse(String invitationId, String status, String challengeTitle,
        String inviterNickname, boolean acceptRequiresLogin, String appLink, String expiresAt, String consentVersion) {}
