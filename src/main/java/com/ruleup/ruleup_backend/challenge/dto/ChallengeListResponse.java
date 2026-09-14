package com.ruleup.ruleup_backend.challenge.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 내 챌린지 목록 응답 — 홈의 참여 중 목록과 마이페이지의 진행 중/완료/이탈 탭이 전부 이 하나를 쓴다.
 *
 * <p>진행 중·완료·이탈 목록 모두 누적될 수 있으므로
 * 커서 페이지네이션을 둔다. 정렬 키는 세 탭 공통으로 <b>종료일 내림차순 + 챌린지 id 내림차순</b>이다 —
 * 완료 방이 하드 삭제된 뒤에는 이력 테이블에서 읽어야 하는데, 두 원천이 함께 가진 안정적인 키가
 * 종료일뿐이기 때문이다.
 */
@Schema(description = "내 챌린지 목록 한 페이지")
public record ChallengeListResponse(

        @Schema(description = "챌린지 목록. 없으면 빈 배열이다.")
        List<Item> challenges,

        @Schema(description = "다음 페이지 커서. 마지막 페이지면 null.")
        String nextCursor,

        @Schema(description = "다음 페이지가 있는지", example = "false")
        boolean hasNext
) {
    /** Completed cards use the same fields from the final history snapshot. Legacy rows may have gaps. */
    @Schema(name = "MyChallengeItem", description = "내 챌린지 카드. 완료 방은 최종 이력에서 조회한다.")
    public record Item(

            @Schema(description = "챌린지 id") String challengeId,

            @Schema(description = "제목. 심사 중·거부면 AI 임시 제목이 내려온다.", example = "매일 아침 5km 러닝")
            String title,

            @Schema(description = "설명. 심사 중·거부면 null(빈칸).") String description,

            @Schema(description = "대표 이미지. 심사 통과 전이면 null(기본 이미지).") String imageUrl,

            @Schema(description = "카테고리(12종)", example = "EXERCISE") String category,

            @Schema(description = "참여 형태", example = "GROUP", allowableValues = {"SOLO", "GROUP"})
            String mode,

            @Schema(description = "공개 범위. 솔로는 null.", example = "PUBLIC",
                    allowableValues = {"PUBLIC", "PRIVATE"})
            String visibility,

            @Schema(description = "챌린지 진행 상태", example = "ACTIVE",
                    allowableValues = {"UPCOMING", "ACTIVE", "COMPLETED"})
            String status,

            @Schema(description = "현재 인원", example = "12") Integer participantCount,

            @Schema(description = "정원. 제한이 없으면 null.", example = "50") Integer capacity,

            @Schema(description = "최소 입장 티어. 제한이 없으면 null.", example = "SILVER") String minTier,

            @Schema(description = "주간 수행 횟수(1~7). 판정 주기는 1주 고정이라 요일 지정은 없다.", example = "5")
            Integer weeklyCount,

            @Schema(description = "시작일", example = "2026-07-03") String startDate,

            @Schema(description = "종료일", example = "2026-07-16") String endDate,

            @Schema(description = "내 역할. 이탈·완료 건은 그 시점의 역할이다.", example = "MEMBER",
                    allowableValues = {"OWNER", "MEMBER"})
            String myRole,

            @Schema(description = "방장 유형", example = "USER", allowableValues = {"USER", "BOT"})
            String ownerType,

            @Schema(description = "해당 챌린지에서의 **내 성공률 0~1**. 판정 이력이 없으면 null "
                    + "(0.0 을 내리지 않는다 — 「기록 없음」과 「0%」는 다른 사실이다).\n\n"
                    + "완료 탭은 종료 시점의 **최종 성공률**이고 진행 중 탭은 현재 시점 값이다. "
                    + "**기간 진척도와는 다른 값이다** — 진척도는 목표 대비이고 성공률은 판정 대비다.",
                    example = "0.88")
            Double successRate,

            @Schema(description = "어떻게 나갔는지. **LEFT 탭에서만** 채워지고 다른 탭에서는 null.\n\n"
                    + "⚠️ **현재 실제로 내려오는 값은 `SELF` 와 `KICK_BY_OWNER` 둘뿐이다.** 저장 컬럼이 "
                    + "`enum('LEAVE','KICK')` 이라 그 이상을 구분할 수 없다. 나머지 5종(`KICK_REPORT` · "
                    + "`KICK_FAIL` · `KICK_PERMISSION` · `AUTO_TIER` · `AUTO_LOCK`)은 자동 강퇴 배치가 "
                    + "생길 때를 위한 예약 값이고 그 배치는 아직 없다 — 즉 지금은 자동 강퇴 자체가 "
                    + "일어나지 않으므로 값이 뭉개진 이력도 없다. 클라이언트는 값이 늘어나도 깨지지 "
                    + "않게 처리해두면 된다.",
                    example = "SELF",
                    allowableValues = {"SELF", "KICK_REPORT", "KICK_FAIL", "KICK_PERMISSION",
                            "KICK_BY_OWNER", "AUTO_TIER", "AUTO_LOCK"})
            String leftType,

            @Schema(description = "이탈 시각. LEFT 탭에서만 채워진다.", example = "2026-07-10T21:00:00Z")
            String leftAt) {}
}
