package com.ruleup.ruleup_backend.challenge.dto;

public final class RoomAdminDtos {
    private RoomAdminDtos() {}
    public record InvitationResponse(String invitationId, String token, String inviteUrl, String expiresAt) {}
    public record KickRequest(String reason) {}
    public record KickResponse(boolean kicked, String userId, String rejoinAvailableAt) {}
    public record TransferRequest(String targetUserId) {}
    public record TransferResponse(String newOwnerUserId, String myRole) {}
    public record ClaimResponse(String myRole, String graceUntil) {}
}
