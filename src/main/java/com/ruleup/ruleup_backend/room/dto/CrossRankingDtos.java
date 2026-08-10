package com.ruleup.ruleup_backend.room.dto;

import java.math.BigDecimal;
import java.util.List;

public final class CrossRankingDtos {
    private CrossRankingDtos() {}
    public record Response(Item myChallenge, List<Item> items, String updatedAt, String nextCursor) {}
    public record Item(int rank, String challengeId, String title, String imageUrl, String mode,
                       BigDecimal successRate, int successCount, int participations) {}
}
