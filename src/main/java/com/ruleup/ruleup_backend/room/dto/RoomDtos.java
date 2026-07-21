package com.ruleup.ruleup_backend.room.dto;

import java.math.BigDecimal;
import java.util.List;

/** 랭킹 · 방 홈 응답 DTO(방 내부기능 §7.3, room). */
public final class RoomDtos {

    private RoomDtos() {}

    // ===== 랭킹 =====
    public record RankingResponse(List<Rank> rankings, MyRank myRank) {
        public record Rank(int rank, String userId, String nickname, BigDecimal progressRate, int successDays) {}
        public record MyRank(int rank, BigDecimal progressRate, BigDecimal gapToAbove) {}
    }

    // ===== 방 홈 =====
    public record RoomResponse(
            String myRole,                  // OWNER / MEMBER
            Summary summary,
            PinnedNotice pinnedNotice,      // nullable
            int unreadNoticeCount,
            List<TopRank> topRanking,
            String myTodayStatus            // VerificationStatus
    ) {
        public record Summary(String title, int completionRate, BigDecimal avgMannerTemperature,
                              int remainingDays, int participantCount) {}
        public record PinnedNotice(String noticeId, String title, boolean pinned, String createdAt, boolean isRead) {}
        public record TopRank(int rank, String userId, String nickname, BigDecimal progressRate) {}
    }
}
