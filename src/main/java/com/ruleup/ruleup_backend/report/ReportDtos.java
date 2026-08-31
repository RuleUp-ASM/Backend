package com.ruleup.ruleup_backend.report;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 신고 접수와 차단 목록 계약 — 방 내부 기능 5-3, 신고 접수 API 명세(2026-08-26 개편). */
public final class ReportDtos {

    private ReportDtos() {}

    @Schema(name = "ReportCreateRequest", description = """
            신고 접수. **사유 선택만 받고 자유 텍스트는 받지 않는다** — 유저가 성실히 적어주지 않아
            판단 재료로 신뢰할 수 없었고, 그 텍스트를 읽던 LLM 접수 필터도 함께 폐지됐다.
            대신 서버가 신고 시점 컨텍스트를 자동 수집해 스냅샷으로 고정한다.""")
    public record CreateRequest(

            @Schema(description = "USER / CHALLENGE — **2갈래뿐**이다", example = "USER",
                    allowableValues = {"USER", "CHALLENGE"}, requiredMode = Schema.RequiredMode.REQUIRED)
            String targetType,

            @Schema(description = "targetType=USER 일 때 필수. 본인은 신고할 수 없다.")
            String targetUserId,

            @Schema(description = """
                    targetType=CHALLENGE 일 때 필수. USER 신고여도 **행위 신고면 발생 챌린지가 필수**다 —
                    카운트 집계용이 아니라 스냅샷에 방 정보를 담기 위함이다.""")
            String targetChallengeId,

            @Schema(description = """
                    신고 진입점 — PROFILE / CHALLENGE_DETAIL / ROOM. 스냅샷에 **어떤 화면에서 신고했는지**를
                    남기는 용도다. NOTICE·COMMENT 는 공지·댓글 기능과 함께 Phase 2.""",
                    example = "PROFILE")
            String contextType,

            @Schema(description = "공지·댓글 신고 시 해당 글 ID — 스냅샷 대상 지정용")
            String contextId,

            @Schema(description = """
                    유저: CHEATING_SUSPECT / INAPPROPRIATE / SPAM_AD / ETC ·
                    챌린지: INAPPROPRIATE / SPAM_AD / ETC.
                    **사유별 처리 차이는 없고** 운영자 검토 시 분류 힌트로만 쓴다.""",
                    example = "INAPPROPRIATE", requiredMode = Schema.RequiredMode.REQUIRED)
            String reason) {}

    @Schema(name = "ReportCreateResponse", description = """
            접수 결과는 **완료 안내만** 한다. 처리 경과·결과는 신고자에게 알리지 않는다 — 익명성과 보복 방지.""")
    public record CreateResponse(

            @Schema(description = "접수된 신고 ID") String reportId,

            @Schema(description = "차단 등재 여부 — 유저·챌린지 신고 모두 항상 true", example = "true")
            boolean blocked,

            @Schema(description = """
                    내 화면 즉시 효과 —
                    `USER_CONTENT_MASKED`(임시 닉네임·기본 이미지·글 미노출) /
                    `CHALLENGE_HIDDEN`(미참여 — 탐색 미노출) /
                    `CHALLENGE_MASKED`(참여 중 — 기본 이미지·AI 임시 제목·설명 빈칸)""",
                    example = "USER_CONTENT_MASKED")
            String hiddenEffect) {}

    @Schema(name = "BlockListResponse", description = "내가 차단한 목록 — 나만 볼 수 있다")
    public record BlockListResponse(List<UserItem> users, List<ChallengeItem> challenges) {}

    @Schema(name = "BlockedUserItem", description = "차단한 사용자 — 실제 닉네임 대신 임시 닉네임으로 보인다")
    public record UserItem(String userId, String maskedNickname,
                           @Schema(example = "2026-08-17T10:00:00Z") String blockedAt) {}

    @Schema(name = "BlockedChallengeItem", description = "차단한 챌린지 — 탐색 목록에서 빠진다")
    public record ChallengeItem(String challengeId, String maskedTitle, boolean participating,
                                @Schema(example = "2026-08-17T10:00:00Z") String blockedAt) {}

    @Schema(name = "BlockDeleteResponse", description = """
            차단 해제 결과. **신고 취소가 아니다** — 신고 건과 스냅샷은 그대로 남는다.""")
    public record DeleteResponse(@Schema(example = "true") boolean removed) {}
}
