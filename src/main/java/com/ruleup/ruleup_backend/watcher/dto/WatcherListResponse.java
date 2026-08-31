package com.ruleup.ruleup_backend.watcher.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 피감시자가 보는 내 감시자 목록. <b>인원 상한이 없다</b>. */
@Schema(name = "WatcherListResponse")
public record WatcherListResponse(List<Item> items) {

    @Schema(name = "WatcherListItem")
    public record Item(
            String watcherId,
            @Schema(description = "감시자의 공개 닉네임") String watcherNickname,
            @Schema(description = "PENDING / ACTIVE", example = "ACTIVE") String status,
            @Schema(description = "동의 시각. 미수락이면 null.") String acceptedAt) {}
}
