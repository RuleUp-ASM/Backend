package com.ruleup.ruleup_backend.room.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

public final class CrossRankingDtos {
    private CrossRankingDtos() {}

    @Schema(name = "CrossRankingResponse", description = "방 밖 랭킹 한 페이지 — 하루 1회 03시 배치 스냅샷")
    public record Response(
            @Schema(description = "요청에 challengeId 를 넘겼을 때 그 방의 순위. 미등재도 ranked=false 로 내려간다.")
            MyChallenge myChallenge,

            @Schema(description = "순위 목록") List<Item> items,

            @Schema(description = "이 수치가 만들어진 시각. 실시간이 아니므로 화면에 함께 보여주면 오해가 줄어든다.",
                    example = "2026-08-17T03:00:00Z")
            String updatedAt,

            @Schema(description = "다음 페이지 커서. null 이면 마지막 페이지다.") String nextCursor) {}

    @Schema(name = "CrossRankingMine", description = "요청한 내 챌린지의 스냅샷 상태")
    public record MyChallenge(String challengeId, Integer rank, boolean ranked,
                              BigDecimal successRate, int totalCount) {}

    @Schema(name = "CrossRankingItem", description = "챌린지 한 줄 — 등재 기준은 그룹 50회 · 솔로 10회 이상 누적 판정")
    public record Item(
            @Schema(example = "1") int rank,
            String challengeId,
            @Schema(example = "새벽 러닝 크루") String title,
            @Schema(description = "현재 참여 인원") int memberCount,
            @Schema(description = "누적 판정 횟수") int totalCount,
            @Schema(description = "방 전체 성공률(0~1)", example = "0.94") BigDecimal successRate) {}
}
