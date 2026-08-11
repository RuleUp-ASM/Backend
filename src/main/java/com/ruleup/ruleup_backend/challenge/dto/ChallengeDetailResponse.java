package com.ruleup.ruleup_backend.challenge.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 공개 상세 응답 — 참여 여부와 무관하게 방의 조건·통계를 보고 들어갈지 결정하는 화면.
 * 멤버 전용 내부 화면은 {@code /room} 이 담당한다.
 *
 * @param owner       봇방장이면 null
 * @param moderation  <b>방장 본인이 조회할 때만</b> 채운다 — 남의 화면에는 심사 상태를 노출하지 않는다
 * @param cloneable   템플릿 복제 가능 여부 — 공개 그룹만 true
 * @param joinNote    {@code NEXT_CYCLE}(사이클 중간 입장 → 다음 주 경계부터 판정) / {@code IMMEDIATE}
 * @param joined      <b>내가 이미 들어가 있는 방인가</b>(방장·시작 전 방 포함). 참여 버튼을 그릴지 말지는
 *                    이 값 하나로 정한다 — {@code joinBlockReason} 은 종료가 우선순위라 종료된 방에서는
 *                    참여 중이어도 {@code CHALLENGE_COMPLETED} 가 내려가므로 참여 여부 판단에 쓰면 안 된다
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ChallengeDetailResponse(
        String challengeId,
        String title,
        String description,
        String imageUrl,
        String category,
        String mode,
        String visibility,
        String status,
        Owner owner,
        String ownerType,
        int participantCount,
        Integer capacity,
        boolean isFull,
        Period period,
        Verification verification,
        Stats stats,
        Gate gate,
        String joinBlockReason,
        String rejoinAvailableAt,
        String joinNote,
        boolean cloneable,
        boolean joined,
        String myRole,
        Moderation moderation
) {
    public record Owner(String userId, String nickname) {}

    public record Period(String start, String end, int remainingDays) {}

    /** @param detail 표시 문구(예: "기상 06:00 ±10분") */
    public record Verification(String type, String method, String detail,
                               List<String> requiredPermissions) {}

    /** 표본이 모자라면 둘 다 null — "아직 참여자가 적어 값을 낼 수 없어요"로 표시한다. */
    public record Stats(Double completionRate, Double retentionRate) {}

    public record Gate(String minTier, String myDisplayTier, boolean eligible) {}

    public record Moderation(String title, String description, String image) {}
}
