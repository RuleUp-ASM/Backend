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
            @Schema(description = "프로필 이미지. 차단·익명이거나 승인 사진이 없으면 null.") String profileImageUrl,
            @Schema(description = "조회자가 이 사용자를 차단했는지") boolean blocked) {}

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

            @Schema(description = "이번 주 사이클의 내 진행도. 클라이언트는 done/summary.weeklyCount 로 "
                    + "\"이번 주 N/M\" 을 그린다.")
            MyWeekly myWeekly,

            @Schema(description = "내 오늘 인증 상태. 오늘이 판정 대상이 아니면 NOT_TARGET.",
                    example = "DONE",
                    allowableValues = {"IN_PROGRESS", "DONE", "FAILED", "NOT_TARGET"})
            String myTodayStatus,

            @Schema(description = "루틴 진행률 — 챌린지 전체 기간 기준의 나와 방 평균. **항상 내려가며 null 이 아니다.**",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            RoutineProgress routineProgress,

            @Schema(description = "Phase 1에서는 항상 null. Phase 2 고정 공지 호환 필드.")
            Object pinnedNotice) {

        /**
         * 진행률 = 성공일 ÷ 목표일 × 100(상한 100). {@code GET /verifications/progress} 의
         * {@code progressRate} 와 같은 값이다 — 두 화면이 다른 숫자를 보이지 않게 저장값을 그대로 쓴다.
         * 목표일은 가입 시점이 아니라 그 방이 처음 처리될 때(자동 인증 방은 첫 sync, 수동 인증 방은 첫
         * 수동 인증) 한 번 계산되므로, 그 전에는 목표일·성공일·진행률이 모두 0 이다.
         */
        @Schema(name = "RoomRoutineProgress", description = "루틴 진행률(챌린지 전체 기간 기준). "
                + "모든 필드는 null 이 아니다. 진행률 두 값은 **0~100 퍼센트**로, summary.roomSuccessRate(0~1)와 "
                + "단위도 계산식도 다른 값이다.")
        public record RoutineProgress(
                @Schema(description = "내 진행률(%, 0~100, 소수 둘째 자리까지) = 성공일 ÷ 목표일 × 100(상한 100). "
                        + "목표일 계산 전이면 0.00.", example = "42.86", requiredMode = Schema.RequiredMode.REQUIRED)
                BigDecimal myProgressRate,
                @Schema(description = "내 성공 일수. 목표일 계산 전이면 0.", example = "6",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                int mySuccessDays,
                @Schema(description = "내 목표 일수(빈도형은 필요 횟수 합). **목표일 계산 전에만 0** — 자동 인증 방은 "
                        + "첫 sync, 수동 인증 방은 첫 수동 인증 때 계산된다(시작 전 방·가입 직후 sync 전이 여기에 "
                        + "해당). 판정 여부와는 무관하며, 한 번 계산되면 최소 1 이고 다시 0 이 되지 않는다.",
                        example = "14", requiredMode = Schema.RequiredMode.REQUIRED)
                int myTargetDays,
                @Schema(description = "방 평균 진행률(%, 0~100, 소수 둘째 자리까지) — 지금 참여 중인 멤버 각자의 "
                        + "진행률 평균. 목표일 계산 전인 멤버는 0 으로 평균에 들어간다. 참여 중인 멤버가 나 혼자면"
                        + "(솔로 방 포함) 항상 myProgressRate 와 같다. summary.roomSuccessRate(판정 대비 성공 비율, "
                        + "0~1)와는 다른 값이다.", example = "37.50", requiredMode = Schema.RequiredMode.REQUIRED)
                BigDecimal roomAverageProgressRate) {}

        @Schema(name = "RoomSummary", description = "방 요약")
        public record Summary(
                @Schema(description = "방 제목", example = "매일 아침 6시 기상") String title,

                @Schema(description = "주간 수행 횟수(1~7). 판정 주기는 1주 고정이라 특정 요일은 지정하지 않는다 — "
                        + "그 주 어느 날이든 성공 N회를 채우면 된다.", example = "7")
                Integer weeklyCount,

                @Schema(description = "방 전체 성공률(0~1) = 참여 중인 멤버 전체의 성공일 ÷ (성공일 + 실패일). "
                        + "**판정이 한 건도 없으면 null** — 0.0 으로 내리면 갓 만든 방과 전원 실패한 방이 같아 보인다. "
                        + "routineProgress.roomAverageProgressRate(목표일 대비 진행률 평균, 0~100)와는 다른 값이다.",
                        example = "0.92")
                BigDecimal roomSuccessRate,

                @Schema(description = "종료까지 남은 일수", example = "14") Integer remainingDays,
                @Schema(description = "현재 참여 인원", example = "14") int participantCount,
                @Schema(description = "정원. 제한이 없으면 null.", example = "50") Integer capacity) {}

        @Schema(name = "RoomMyWeekly", description = "이번 주 사이클의 내 진행도")
        public record MyWeekly(
                @Schema(description = "이번 주 성공 횟수(0 ~ weeklyCount)", example = "4") int done,
                @Schema(description = "사이클 시작일(KST)", example = "2026-07-13") String weekStart,
                @Schema(description = "사이클 종료일(KST)", example = "2026-07-19") String weekEnd,
                @Schema(description = "이번 주 판정 대상 여부. 사이클 중간 입장이라 다음 주부터 판정되거나 "
                        + "방이 아직 시작 전이면 false 이고, 이때 done 은 0 이다.", example = "true")
                boolean judging) {}

        @Schema(name = "RoomTopRank", description = "상위 랭킹 한 줄 — 차단한 사람은 가려진 채로 남는다")
        public record TopRank(
                @Schema(example = "1") int rank,
                String userId,
                @Schema(description = "표시 닉네임. 차단·익명이면 가려진 값.") String nickname,
                @Schema(description = "프로필 이미지. 없거나 가려지면 null.") String profileImageUrl,
                @Schema(example = "0.98") BigDecimal successRate,
                @Schema(description = "조회자가 이 사용자를 차단했는지") boolean blocked) {}
    }
}
