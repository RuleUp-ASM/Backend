package com.ruleup.ruleup_backend.watcher.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 응원·놀림 — 실패 건당 1회. */
public final class WatcherReactionDtos {

    private WatcherReactionDtos() {}

    @Schema(name = "WatcherReactionRequest")
    public record Request(
            @Schema(description = "CHEER 또는 TEASE **2종뿐**. 그 외 값은 400.",
                    example = "CHEER", requiredMode = Schema.RequiredMode.REQUIRED)
            String reaction) {}

    @Schema(name = "WatcherReactionResponse")
    public record Response(
            String noticeId,
            String reaction,
            @Schema(description = "반응한 감시자 닉네임 — **공개된다**") String reactorNickname,
            String reactedAt) {}
}
