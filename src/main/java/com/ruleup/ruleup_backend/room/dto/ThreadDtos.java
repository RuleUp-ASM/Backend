package com.ruleup.ruleup_backend.room.dto;

import java.util.List;

public final class ThreadDtos {
    private ThreadDtos() {}

    public record Response(PinnedNotice pinnedNotice, List<Item> items, String nextCursor) {}
    public record PinnedNotice(String noticeId, String title, String content, String createdAt) {}
    public record Item(String type, String id, User user, String at, Integer streak,
                       String failDate, String title, long commentCount) {}
    public record User(String userId, String nickname, String profileImageUrl, boolean blocked) {}
}
