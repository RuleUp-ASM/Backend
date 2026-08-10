package com.ruleup.ruleup_backend.room.dto;

import java.math.BigDecimal;
import java.util.List;

public final class RoomDtos {
    private RoomDtos() {}

    public record User(String userId, String nickname, String profileImageUrl) {}
    public record RankingResponse(Me me, List<Item> items) {
        public record Me(Integer rank, boolean ranked, BigDecimal successRate,
                         int participations, BigDecimal gapToFirst) {}
        public record Item(Integer rank, User user, BigDecimal successRate,
                           int successCount, int participations) {}
    }
    public record RoomResponse(String myRole, String ownerType, Summary summary,
                               PinnedNotice pinnedNotice, List<TopRank> topRanking,
                               String myTodayStatus) {
        public record Summary(String title, BigDecimal roomSuccessRate, int remainingDays,
                              int participantCount, Integer capacity) {}
        public record PinnedNotice(String noticeId, String title, String createdAt) {}
        public record TopRank(int rank, String userId, String nickname,
                              String profileImageUrl, BigDecimal successRate) {}
    }
}
