package com.ruleup.ruleup_backend.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 백오피스 요청·응답. 전용 클라이언트 형태가 미정이라 입력·출력과 불변식만 고정한다. */
public final class AdminDtos {

    private AdminDtos() {}

    // ===== 신고 검토 =====

    @Schema(name = "AdminReportQueueResponse", description = """
            검토 큐 — **피신고자 또는 챌린지 단위로 묶어** 내린다. 같은 대상에 대한 신고를 하나씩
            보면 판단이 느려지고 같은 사안을 여러 번 판단하게 된다. **처리 기한은 없다.**""")
    public record ReportQueueResponse(List<Group> items) {}

    @Schema(name = "AdminReportGroup")
    public record Group(
            @Schema(description = "USER / CHALLENGE") String targetType,
            String targetId,
            @Schema(description = "피신고자 닉네임 또는 챌린지 제목") String targetLabel,
            @Schema(description = "묶인 신고 건수") int reportCount,
            @Schema(description = "가장 이른 접수 시각 — 정렬 기준") String firstReportedAt,
            @Schema(description = "미검토 신고 ID 목록") List<String> reportIds) {}

    @Schema(name = "AdminReportDetailResponse", description = """
            신고 상세 — **신고 시점 스냅샷**이다. 원본이 수정·삭제돼도 이 값으로 검토한다.
            **신고자 신원은 응답에 없다.**""")
    public record ReportDetail(
            String reportId,
            String targetType,
            String targetId,
            String reason,
            String status,
            String createdAt,
            @Schema(description = "접수 시점에 고정된 대상 콘텐츠·프로필·방 정보") Object snapshot) {}

    @Schema(name = "AdminReportResolveRequest")
    public record ResolveRequest(
            @Schema(description = "NO_ACTION(문제없음 종결) / SANCTIONED(제재로 진행)",
                    example = "NO_ACTION", requiredMode = Schema.RequiredMode.REQUIRED)
            String resolution) {}

    @Schema(name = "AdminReportResolveResponse")
    public record ResolveResponse(String reportId, String status, String resolvedAt) {}

    // ===== 제재 =====

    @Schema(name = "AdminSanctionRequest", description = """
            제재 집행. **사유 입력이 필수**이며 고지 알림과 재검토 대응의 근거가 된다.
            `confirmationToken` 없이 보내면 428 과 함께 재확인용 토큰이 내려온다.""")
    public record SanctionRequest(
            @Schema(description = "FEATURE_SUSPENSION / LOCK / BAN", example = "LOCK",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String type,
            @Schema(description = "기능 정지의 대상 — type 이 FEATURE_SUSPENSION 일 때만 쓴다")
            String featureCode,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String reasonCode,
            @Schema(description = "운영자 입력 사유 — **필수**", requiredMode = Schema.RequiredMode.REQUIRED)
            String reasonText,
            @Schema(description = "REPORT / ANOMALY / DIRECT — **검토 근거 추적의 핵심**")
            String source,
            @Schema(description = "근거가 된 신고·이상탐지 ID") String sourceId,
            @Schema(description = "2단계 확인 토큰") String confirmationToken) {}

    @Schema(name = "AdminSanctionPreview", description = "재확인 화면에 그대로 보여줄 요약")
    public record SanctionPreview(
            String targetUserId,
            String targetNickname,
            String type,
            String reasonCode,
            String reasonText,
            @Schema(description = "해제 예정 시각. 영구 정지는 null.") String endsAt) {}

    @Schema(name = "AdminSanctionResponse")
    public record SanctionResponse(
            String sanctionId,
            String targetUserId,
            String type,
            String accountStatus,
            String startsAt,
            String endsAt,
            @Schema(description = "필수(A) 고지 발행 시각 — null 이면 가드레일 위반이다")
            String notifiedAt) {}

    // ===== 유저 통합 뷰 =====

    @Schema(name = "AdminUserViewResponse", description = """
            판단 근거 통합 뷰. **자동 제재와 직권 제재를 별개 배열로** 내리며 합산하지 않는다 —
            성격이 달라 섞으면 재범 판정이 불공정해진다. 판단에 불필요한 항목은 넣지 않는다.""")
    public record UserView(
            String userId,
            String nickname,
            String accountStatus,
            List<SanctionItem> adminSanctions,
            List<SanctionItem> autoSanctions,
            List<AnomalyItem> anomalies,
            @Schema(description = "이 유저를 대상으로 접수된 신고 건수") long reportCount) {}

    @Schema(name = "AdminSanctionItem")
    public record SanctionItem(
            String sanctionId, String type, String reasonCode, String source,
            String startsAt, String endsAt, String revokedAt) {}

    // ===== 이상탐지 =====

    @Schema(name = "AdminAnomalyResponse", description = "**탐지만으로는 제재하지 않는다** — 검토 대상 목록일 뿐이다")
    public record AnomalyResponse(List<AnomalyItem> items) {}

    @Schema(name = "AdminAnomalyItem")
    public record AnomalyItem(
            String signalId, String signalType, String targetUserId,
            int score, String detectedAt, String reviewedAt) {}

    // ===== 직권 폐쇄 =====

    @Schema(name = "AdminChallengeCloseRequest")
    public record CloseRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String reasonText,
            String confirmationToken) {}

    @Schema(name = "AdminChallengeClosePreview", description = "영향 인원 수를 먼저 보여줘 오조작을 막는다")
    public record ClosePreview(
            String challengeId, String title,
            @Schema(description = "자동 탈퇴될 일반 참여자 수") int affectedMemberCount) {}

    @Schema(name = "AdminChallengeCloseResponse")
    public record CloseResponse(String challengeId, String status, int affectedMemberCount) {}

    // ===== 장애 구제 =====

    @Schema(name = "AdminOutageReliefRequest", description = """
            **성공 처리가 아니라 분모에서 제외**하는 중립 처리다.""")
    public record ReliefRequest(
            @Schema(example = "2026-08-30T00:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
            String periodStart,
            @Schema(example = "2026-08-30T06:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
            String periodEnd,
            @Schema(description = "ALL / VERIFY_TYPE", example = "ALL") String scope,
            String confirmationToken) {}

    @Schema(name = "AdminOutageReliefResponse")
    public record ReliefResponse(String reliefId, String scope, int affectedCount, String appliedAt) {}

    // ===== 운영 공지 =====

    @Schema(name = "AdminNoticeRequest", description = "**필수(A) 알림으로 나간다** — 끌 수 없고 야간에도 즉시 발송된다")
    public record NoticeRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String title,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String body,
            String confirmationToken) {}

    @Schema(name = "AdminNoticeResponse")
    public record NoticeResponse(int recipientCount, String publishedAt) {}
}
