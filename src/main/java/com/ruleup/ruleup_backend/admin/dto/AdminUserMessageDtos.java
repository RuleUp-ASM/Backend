package com.ruleup.ruleup_backend.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 운영자 → 회원 개별 알림 계약(2026-11-11).
 *
 * <p>이의 사유 칸에 문의를 적는 등 <b>답할 통로가 없는 사람</b>에게 운영자가 먼저 말을 건다. 받는 사람을
 * 고르는 검색이 함께 있고, 최근 이의·신고·문의를 한 사람이 위에 온다 — 운영자가 연락할 일이 생기는
 * 사람이 대개 그들이다.
 */
public final class AdminUserMessageDtos {

    private AdminUserMessageDtos() {}

    @Schema(name = "AdminUserSearchResponse", description = """
            회원 검색 결과. **최근 30일에 이의·신고·문의를 한 사람이 위**에 오고, 그 안에서는 마지막 활동이 최근인 순이다.
            검색어가 없으면 그 사람들만 내려간다. 탈퇴자·운영자 계정은 빠진다.""")
    public record SearchResponse(List<UserItem> items,
                                 @Schema(description = "최근 활동을 세는 기간(일)") int recentDays) {}

    @Schema(name = "AdminUserSearchItem")
    public record UserItem(
            String userId,
            @Schema(description = "승인된 닉네임") String nickname,
            @Schema(description = "ACTIVE / SUSPENDED") String status,
            @Schema(description = "가입 시각") String joinedAt,
            @Schema(description = "최근 30일 이의 건수") int appealCount,
            @Schema(description = "최근 30일 이 사람이 접수한 신고 건수") int reportCount,
            @Schema(description = "최근 30일 문의 건수") int inquiryCount,
            @Schema(description = "그중 아직 답변하지 않은 문의 건수") int openInquiryCount,
            @Schema(description = "최근 30일 마지막 이의·신고·문의 시각. 없으면 null") String lastActionAt,
            @Schema(description = "마지막 활동 종류 — APPEAL / REPORT / INQUIRY. 없으면 null") String lastActionType) {}

    @Schema(name = "AdminUserMessageRequest", description = """
            회원 한 명에게 보낼 알림. 제목 1~50자, 본문 1~500자.
            **잠금화면에 그대로 뜬다** — 제재 사유·신고 내용 같은 민감한 말은 넣지 않는다.""")
    public record MessageRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "RuleUp 운영팀이에요") String title,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    example = "남겨주신 이의 내용 확인했어요. 문의는 마이페이지 > 고객센터로 남겨주시면 답변드릴게요.") String body) {}

    @Schema(name = "AdminUserMessageResponse", description = """
            적재 결과. **푸시 도착을 뜻하지 않는다** — 받는 사람의 알림 설정·기기 등록 여부에 따라 알림함에만 남을 수 있다.""")
    public record MessageResponse(
            @Schema(description = "알림함 행 ID") String notificationId,
            String userId,
            String nickname,
            String sentAt,
            @Schema(description = "21:00~08:00(KST)라 푸시가 다음 날 08:00 으로 미뤄지는지") boolean pushDeferredToMorning) {}

    @Schema(name = "AdminSentMessageListResponse", description = """
            운영자가 보낸 개별 알림 — 최신순. `nextCursor` 가 있으면 그 값을 `cursor` 로 넘겨 다음 장을 받는다.""")
    public record SentListResponse(List<SentItem> items, String nextCursor) {}

    @Schema(name = "AdminSentMessageItem")
    public record SentItem(
            @Schema(description = "알림함 행 ID") String notificationId,
            String userId,
            @Schema(description = "받은 회원의 지금 닉네임. 탈퇴했으면 null") String nickname,
            @Schema(description = "ACTIVE / SUSPENDED / WITHDRAWN") String status,
            String title,
            String body,
            String sentAt,
            @Schema(description = "푸시가 기기에 전달된 시각. null 이면 알림함에만 있다(기기 미등록·알림 꺼짐·야간 보류 중)")
            String pushedAt) {}
}
