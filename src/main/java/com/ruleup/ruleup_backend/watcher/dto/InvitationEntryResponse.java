package com.ruleup.ruleup_backend.watcher.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 초대 카드 조회 응답. <b>이 호출만으로는 어떤 동의도 성립하지 않는다</b> — 수락은 별도 API 다.
 */
@Schema(name = "WatcherInvitationEntryResponse")
public record InvitationEntryResponse(
        String invitationId,
        @Schema(description = "대상 챌린지 이름 — 심사 대체 규칙이 반영된 공개 제목") String challengeTitle,
        @Schema(description = "초대한 사람의 공개 닉네임") String inviterNickname,
        String expiresAt,
        @Schema(description = "이미 수락된 초대인지") boolean accepted) {}
