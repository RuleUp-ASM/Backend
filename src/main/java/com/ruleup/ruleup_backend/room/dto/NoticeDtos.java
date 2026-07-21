package com.ruleup.ruleup_backend.room.dto;

import java.util.List;

/** 공지 API 요청/응답 DTO 묶음(방 내부기능 §7.1~7.2). */
public final class NoticeDtos {

    private NoticeDtos() {}

    // ===== 목록 =====
    public record ListResponse(List<Item> notices) {
        public record Item(String noticeId, String title, String preview,
                           boolean pinned, boolean isRead, String createdAt) {}
    }

    // ===== 생성 =====
    public record CreateRequest(String title, String content, Boolean pinned) {
        public boolean pinnedOrFalse() { return Boolean.TRUE.equals(pinned); }
    }
    public record CreateResponse(String noticeId, boolean pinned, String createdAt) {}

    // ===== 상세 =====
    public record DetailResponse(String noticeId, String title, String content, boolean pinned,
                                 Author author, String createdAt, String updatedAt) {
        public record Author(String nickname, String profileImageUrl) {}
    }

    // ===== 수정 =====
    public record EditRequest(String title, String content, Boolean resetRead) {
        public boolean resetReadOrFalse() { return Boolean.TRUE.equals(resetRead); }
    }
    public record EditResponse(String noticeId, String updatedAt, boolean readReset) {}

    // ===== 고정 =====
    public record PinRequest(Boolean pinned) {
        public boolean pinnedOrFalse() { return Boolean.TRUE.equals(pinned); }
    }
    public record PinResponse(String noticeId, boolean pinned, String unpinnedNoticeId) {}
}
