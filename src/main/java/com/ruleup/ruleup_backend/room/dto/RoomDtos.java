package com.ruleup.ruleup_backend.room.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

public final class RoomDtos {
    private RoomDtos() {}

    @Schema(name = "RankingUser", description = "랭킹에 실리는 사용자. 차단·익명 규칙에 따라 가려진 값일 수 있다.")
    public record User(
            @Schema(description = "사용자 id") String userId,
            @Schema(description = "표시 닉네임. 차단한 사람이면 임시 닉네임, 익명 챌린지면 마스킹된 값.") String nickname,
            @Schema(description = "프로필 이미지. 차단·익명이거나 승인 사진이 없으면 null.") String profileImageUrl) {}

    @Schema(name = "RoomRankingResponse", description = "방 안 랭킹 — 참여일 이후 전체 성공률 기준")
    public record RankingResponse(
            @Schema(description = "내 순위 요약") Me me,
            @Schema(description = "전체 순위. 미등재자는 등재자 뒤에 붙는다.") List<Item> items) {

        @Schema(name = "RoomRankingMe", description = "내 순위 — 목록을 끝까지 넘기지 않아도 내 위치를 보여줄 수 있게 따로 준다")
        public record Me(
                @Schema(description = "내 순위. 10회 미만 참여면 null(화면에는 \"-\").", example = "3") Integer rank,
                @Schema(description = "등재 여부. 10회 이상 참여해야 true.", example = "true") boolean ranked,
                @Schema(description = "내 성공률(0~1). 미등재면 null.", example = "0.8") BigDecimal successRate,
                @Schema(description = "내 누적 판정 횟수(성공+실패)", example = "10") int participations,
                @Schema(description = "1위와의 성공률 차이. 미등재면 null.", example = "0.18") BigDecimal gapToFirst) {}

        @Schema(name = "RoomRankingItem", description = "랭킹 한 줄. rank 가 null 이면 10회 미만이라 미등재이며 화면에는 \"-\" 로 표시한다.")
        public record Item(
                @Schema(description = "순위. 동점이면 같은 값을 공유하고, 미등재면 null.", example = "1") Integer rank,
                @Schema(description = "대상 사용자") User user,
                @Schema(description = "성공률(0~1). 미등재면 null.", example = "0.98") BigDecimal successRate,
                @Schema(description = "성공 횟수 — 성공률 동점 시 1차 정렬 기준", example = "49") int successCount,
                @Schema(description = "누적 판정 횟수(성공+실패)", example = "50") int participations) {}
    }

    /** 방 홈 일괄 조회. 읽음 필드와 고정 공지는 없다 — 전자는 정책상 영구 미제공, 후자는 Phase 2. */
    @Schema(name = "RoomResponse", description = "방 진입 화면을 한 번에 채우는 일괄 조회")
    public record RoomResponse(

            @Schema(description = "내 역할. OWNER 면 멤버 관리 진입점을 노출한다.",
                    example = "MEMBER", allowableValues = {"OWNER", "MEMBER"})
            String myRole,

            @Schema(description = "방장 유형. BOT 이면 자리가 비어 있다는 뜻이라 \"방장 되기\" 버튼을 노출한다.",
                    example = "USER", allowableValues = {"USER", "BOT"})
            String ownerType,

            @Schema(description = "요약 스탯") Summary summary,

            @Schema(description = "상위 3위. 10회 이상 참여자만 등재되므로 초반에는 빈 배열이 정상이다.")
            List<TopRank> topRanking,

            @Schema(description = "내 오늘 인증 상태. 오늘이 인증 대상일이 아니면 NOT_TARGET.",
                    example = "DONE")
            String myTodayStatus) {

        @Schema(name = "RoomSummary", description = "방 요약")
        public record Summary(
                @Schema(description = "방 제목", example = "매일 아침 6시 기상") String title,

                @Schema(description = "방 전체 성공률(0~1). **판정이 한 건도 없으면 null** — 0.0 으로 내리면 "
                        + "갓 만든 방과 전원 실패한 방이 같아 보인다.", example = "0.92")
                BigDecimal roomSuccessRate,

                @Schema(description = "종료까지 남은 일수", example = "14") int remainingDays,
                @Schema(description = "현재 참여 인원", example = "14") int participantCount,
                @Schema(description = "정원. 제한이 없으면 null.", example = "50") Integer capacity) {}

        @Schema(name = "RoomTopRank", description = "상위 랭킹 한 줄 — 차단한 사람은 가려진 채로 남는다")
        public record TopRank(
                @Schema(example = "1") int rank,
                String userId,
                @Schema(description = "표시 닉네임. 차단·익명이면 가려진 값.") String nickname,
                @Schema(description = "프로필 이미지. 없거나 가려지면 null.") String profileImageUrl,
                @Schema(example = "0.98") BigDecimal successRate) {}
    }
}
