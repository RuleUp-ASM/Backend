package com.ruleup.ruleup_backend.report;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public final class ReportDtos {
    private ReportDtos() {}

    @Schema(name = "ReportCreateRequest", description = "신고 접수 요청. targetType 에 따라 채우는 id 가 달라진다.")
    public record CreateRequest(

            @Schema(description = "신고 대상 종류", example = "USER",
                    allowableValues = {"USER", "CHALLENGE"}, requiredMode = Schema.RequiredMode.REQUIRED)
            String targetType,

            @Schema(description = "targetType=USER 일 때 필수. 본인은 신고할 수 없다.")
            String targetUserId,

            @Schema(description = "targetType=CHALLENGE 일 때 필수. USER 신고에 함께 보내면 "
                    + "\"어느 방에서 있었던 일인지\"가 기록에 남는다.")
            String targetChallengeId,

            @Schema(description = "신고가 발생한 화면", example = "ROOM",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String contextType,

            @Schema(description = "신고한 공지·댓글·인증 이벤트 등의 id. 화면 전체 신고면 null.")
            String contextId,

            @Schema(description = "신고 사유", example = "ABUSE",
                    allowableValues = {"CHEATING_SUSPECT", "INAPPROPRIATE", "SPAM_AD", "ETC",
                            "SPAM", "ABUSE", "HATE", "SEXUAL", "VIOLENCE", "OTHER"},
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String reason,

            @Schema(description = "상세 내용(최대 1000자). 모든 신고에서 필수다.",
                    example = "반복적인 모욕적인 표현입니다.")
            String detail) {}

    @Schema(name = "ReportCreateResponse", description = "신고 접수 결과 — 심사를 기다리지 않고 즉시 차단까지 끝난다")
    public record CreateResponse(
            @Schema(description = "접수된 신고 id") String reportId,

            @Schema(description = "같은 대상을 1주 안에 다시 신고했는지. true 면 차단은 유지되지만 "
                    + "제재 카운트에는 반영되지 않는다.", example = "false")
            boolean duplicate,

            @Schema(description = "개인 차단 등재 여부. 접수 즉시 true 다.", example = "true")
            boolean blacklisted,

            @Schema(description = "내 화면에서 이 대상이 어떻게 가려지는지에 대한 안내 문구")
            String hiddenEffect) {}

    @Schema(description = "내가 차단한 목록 — 나만 볼 수 있다")
    public record BlacklistResponse(List<UserItem> users, List<ChallengeItem> challenges) {}

    @Schema(name = "BlacklistUserItem", description = "차단한 사용자")
    public record UserItem(String userId, String maskedNickname,
                           @Schema(example = "2026-08-17T10:00:00Z") String reportedAt) {}

    @Schema(name = "BlacklistChallengeItem", description = "차단한 챌린지 — 탐색 목록에서 빠진다")
    public record ChallengeItem(String challengeId, String maskedTitle, boolean participating,
                                @Schema(example = "2026-08-17T10:00:00Z") String reportedAt) {}

    @Schema(name = "BlacklistDeleteResponse", description = "차단 해제 결과. 신고 기록과 제재 카운트는 지워지지 않는다.")
    public record DeleteResponse(@Schema(example = "true") boolean removed) {}
}
