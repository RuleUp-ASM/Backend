package com.ruleup.ruleup_backend.watcher.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 수락 응답 — 이 시점부터 룰업이 통지를 보낼 수 있다. */
@Schema(name = "WatcherAcceptResponse")
public record WatcherAcceptResponse(
        @Schema(description = "성립된 감시 관계 ID") String watcherId,
        @Schema(description = "PENDING / ACTIVE", example = "ACTIVE") String status,
        String challengeTitle,
        @Schema(description = "감시 대상(초대한 사람)의 공개 닉네임") String targetNickname,
        @Schema(description = "**동의 시각 — 입증 책임의 근거**") String acceptedAt) {}
