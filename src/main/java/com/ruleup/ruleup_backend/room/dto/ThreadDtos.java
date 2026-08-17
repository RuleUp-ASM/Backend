package com.ruleup.ruleup_backend.room.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 방 스레드 피드 응답(Phase 1).
 *
 * <p>공지·댓글이 Phase 2로 빠지면서 피드의 원천은 인증 판정 하나뿐이다.
 * 고정 공지는 Phase 1 동안 항상 null 이지만 클라이언트 응답 호환을 위해 필드는 유지한다.
 */
public final class ThreadDtos {
    private ThreadDtos() {}

    @Schema(name = "ThreadFeedResponse", description = "인증 이벤트 피드 한 페이지(최신 먼저)")
    public record Response(
            @Schema(description = "Phase 1에서는 항상 null. Phase 2 공지 배너 호환 필드.")
            Object pinnedNotice,

            @Schema(description = "피드 아이템. 첫 판정 전에는 빈 배열이다.")
            List<Item> items,

            @Schema(description = "다음 페이지 커서. null 이면 마지막 페이지다.")
            String nextCursor) {}

    @Schema(name = "ThreadItem", description = "피드 아이템 하나. type 으로 갈라 읽는다.")
    public record Item(

            @Schema(description = "아이템 종류. Phase 2 에서 NOTICE 가 추가될 수 있다.",
                    example = "VERIFY_SUCCESS", allowableValues = {"VERIFY_SUCCESS", "VERIFY_FAIL"})
            String type,

            @Schema(description = "아이템 식별자(인증 건 id)")
            String id,

            @Schema(description = "이 이벤트의 주인공")
            User user,

            @Schema(description = "피드에 실린 시각. 성공은 확정 시각이고, **실패는 공유된 시각**이라 실제로 "
                    + "실패한 날짜와 다르다. 화면 표기는 failDate 를 쓴다.",
                    example = "2026-08-17T06:58:00Z")
            String at,

            @Schema(description = "연속 성공 일수. 성공 이벤트에만 실리고 실패면 null.", example = "12")
            Integer streak,

            @Schema(description = "실제로 실패한 날짜. 실패 이벤트는 이의 기간(1일)이 지난 뒤에 도착하므로 "
                    + "이 값으로 \"○월 ○일 루틴을 실패했습니다\"처럼 과거형으로 표시한다. 성공이면 null.",
                    example = "2026-08-14")
            String failDate) {}

    @Schema(name = "ThreadItemUser", description = "이벤트 작성자. 내가 차단한 사람이면 가려진 값이 온다.")
    public record User(
            @Schema(description = "사용자 id. 차단 여부와 무관하게 항상 실린다.") String userId,

            @Schema(description = "표시 닉네임. 차단한 사람이면 임시 닉네임(계정 id 끝 8자)이다.", example = "김지수")
            String nickname,

            @Schema(description = "프로필 이미지. 차단했거나 승인된 사진이 없으면 null(기본 이미지).")
            String profileImageUrl,

            @Schema(description = "내가 차단한 사람인지. true 면 닉네임·이미지가 가려진 값이며, "
                    + "목록에서 빠지지는 않는다.", example = "false")
            boolean blocked) {}
}
