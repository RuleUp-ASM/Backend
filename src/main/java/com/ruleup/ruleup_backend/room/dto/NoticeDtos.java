package com.ruleup.ruleup_backend.room.dto;

import java.util.List;

/** 공지 API 문서의 순수 조회·커서 페이지 계약. */
public final class NoticeDtos {
    private NoticeDtos() {}

    public record Author(String userId, String nickname, String profileImageUrl) {}
    public record ListResponse(List<Item> items, String nextCursor) {
        public record Item(String noticeId, String title, String preview, boolean pinned,
                           Author author, long commentCount, String createdAt, String updatedAt) {}
    }
    public record CreateRequest(String title, String content, Boolean sendPush) {
        public boolean sendPushOrFalse() { return Boolean.TRUE.equals(sendPush); }
    }
    public record CreateResponse(String noticeId, String createdAt) {}
    public record DetailResponse(String noticeId, String title, String content, boolean pinned,
                                 Author author, long commentCount, String createdAt, String updatedAt) {}
    public record EditRequest(String title, String content) {}
    public record EditResponse(String noticeId, String updatedAt) {}
    public record PinRequest(Boolean pinned) {
        public boolean pinnedOrFalse() { return Boolean.TRUE.equals(pinned); }
    }
    public record PinResponse(String noticeId, boolean pinned, String unpinnedNoticeId) {}
}
