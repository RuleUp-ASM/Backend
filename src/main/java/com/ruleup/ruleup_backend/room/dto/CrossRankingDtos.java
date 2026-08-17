package com.ruleup.ruleup_backend.room.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

public final class CrossRankingDtos {
    private CrossRankingDtos() {}

    @Schema(name = "CrossRankingResponse", description = "방 밖 랭킹 한 페이지 — 하루 1회 03시 배치 스냅샷")
    public record Response(
            @Schema(description = "요청에 challengeId 를 넘겼을 때 그 방의 순위. 미등재거나 생략하면 null.")
            Item myChallenge,

            @Schema(description = "순위 목록") List<Item> items,

            @Schema(description = "이 수치가 만들어진 시각. 실시간이 아니므로 화면에 함께 보여주면 오해가 줄어든다.",
                    example = "2026-08-17T03:00:00Z")
            String updatedAt,

            @Schema(description = "다음 페이지 커서. null 이면 마지막 페이지다.") String nextCursor) {}

    @Schema(name = "CrossRankingItem", description = "챌린지 한 줄 — 등재 기준은 그룹 50회 · 솔로 10회 이상 누적 판정")
    public record Item(
            @Schema(example = "1") int rank,
            String challengeId,
            @Schema(example = "새벽 러닝 크루") String title,
            @Schema(description = "대표 이미지. 없으면 null.") String imageUrl,
            @Schema(example = "GROUP", allowableValues = {"GROUP", "SOLO"}) String mode,
            @Schema(description = "방 전체 성공률(0~1)", example = "0.94") BigDecimal successRate,
            @Schema(description = "누적 성공 횟수", example = "470") int successCount,
            @Schema(description = "누적 판정 횟수(성공+실패)", example = "500") int participations) {}
}
