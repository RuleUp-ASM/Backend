package com.ruleup.ruleup_backend.watcher.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 마이페이지 패널티 수신 관리 — 조회와 토글. */
public final class MyWatchingDtos {

    private MyWatchingDtos() {}

    @Schema(name = "MyWatchingResponse", description = """
            내가 감시자로 등록된 관계 목록. **조회 전용**이며 해제 엔드포인트를 두지 않는다 —
            관계는 루틴 종료 시 자동 제거되고 수신은 토글로 닫는다.""")
    public record ListResponse(List<Item> items) {}

    @Schema(name = "MyWatchingItem")
    public record Item(
            String watcherId,
            @Schema(description = "대상 챌린지 이름") String challengeTitle,
            @Schema(description = "감시 대상의 공개 닉네임") String targetNickname,
            @Schema(description = "PENDING / ACTIVE", example = "ACTIVE") String status,
            @Schema(description = "**false 여도 알림함 적재는 유지**된다 — 푸시만 닫힌다")
            boolean pushEnabled,
            String acceptedAt) {}

    @Schema(name = "MyWatchingPatchRequest", description = """
            수신 토글. **관계를 끊는 것이 아니다** — 구 계약의 revoke 는 해제 개념과 함께 폐지됐다.""")
    public record PatchRequest(
            @Schema(description = "푸시 수신 여부", requiredMode = Schema.RequiredMode.REQUIRED)
            Boolean pushEnabled) {}

    @Schema(name = "MyWatchingPatchResponse")
    public record PatchResponse(
            String watcherId,
            String status,
            boolean pushEnabled,
            @Schema(description = "알림함 유지 여부 — 항상 true 다", example = "true") boolean inboxKept) {}
}
