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
    /** 방 홈 일괄 조회. 읽음 필드와 고정 공지는 없다 — 전자는 정책상 영구 미제공, 후자는 Phase 2. */
    public record RoomResponse(String myRole, String ownerType, Summary summary,
                               List<TopRank> topRanking, String myTodayStatus) {
        public record Summary(String title, BigDecimal roomSuccessRate, int remainingDays,
                              int participantCount, Integer capacity) {}
        public record TopRank(int rank, String userId, String nickname,
                              String profileImageUrl, BigDecimal successRate) {}
    }
}
