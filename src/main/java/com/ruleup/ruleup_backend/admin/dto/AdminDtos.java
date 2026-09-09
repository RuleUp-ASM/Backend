package com.ruleup.ruleup_backend.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 백오피스 요청·응답 — 백오피스 공통 5-2 · <b>5-2-1(2026-09-09 확정)</b>.
 *
 * <p>전용 웹 콘솔로 구현 형태가 확정되면서 표의 경로는 그대로 두고 상세만 채워졌다. 여기 값은
 * 그 확정 계약이다. 프로토타입이 다르게 잡고 있던 지점(§D)은 전부 문서 쪽으로 정리됐다 —
 * 신고자 신원 필드 제거, {@code REVIEWING} 상태 삭제, 클라이언트 모달 대신 <b>서버 주도 428</b>.
 */
public final class AdminDtos {

    private AdminDtos() {}

    // ===== 신고 검토 =====

    @Schema(name = "AdminReportQueueResponse", description = """
            검토 큐(건별). 커서는 **불투명 문자열**이며 정렬은 우선순위다 — **처리 기한 필드를 두지 않는다.**
            기한을 두면 오래된 건이 자동으로 위로 올라와 심각도와 무관한 순서가 만들어진다.""")
    public record ReportQueueResponse(List<ReportItem> items, String nextCursor, long totalCount) {}

    @Schema(name = "AdminReportItem")
    public record ReportItem(
            String reportId,
            @Schema(description = "USER / CHALLENGE") String targetType,
            String targetId,
            @Schema(description = "피신고자 닉네임 또는 챌린지 제목") String targetLabel,
            String reason,
            String status,
            String createdAt,
            @Schema(description = "종결 시각. 미종결이면 null") String resolvedAt,
            @Schema(description = "같은 대상에 쌓인 신고 수 — **참고 지표로만** 쓴다. 임계값이 아니다")
            int targetReportCount,
            @Schema(description = """
                    접수자 남용 의심 여부. **신고자 신원은 어떤 응답에도 없다** —
                    상세는 이상탐지(`/anomalies`)가 담당하고 여기서는 불리언 하나만 나간다""")
            boolean reporterFlagged) {}

    @Schema(name = "AdminReportTargetsResponse", description = """
            **대상 단위로 묶은** 큐. 건별 목록과 페이징 단위가 달라 한 응답에 담기 어려워 경로를 나눴다.
            같은 대상에 대한 신고를 하나씩 보면 판단이 느려지고 같은 사안을 여러 번 판단하게 된다.""")
    public record ReportTargetsResponse(List<TargetGroup> items) {}

    @Schema(name = "AdminReportTargetGroup")
    public record TargetGroup(
            String targetType,
            String targetId,
            String targetLabel,
            @Schema(description = "묶인 미검토 신고 건수") int reportCount,
            @Schema(description = "가장 이른 접수 시각") String firstReportedAt,
            @Schema(description = "가장 늦은 접수 시각") String lastReportedAt,
            @Schema(description = "사유별 건수 — 무엇이 반복되는지가 판단의 첫 단서다")
            List<ReasonCount> reasons,
            @Schema(description = "미검토 신고 ID 목록") List<String> reportIds) {}

    @Schema(name = "AdminReportReasonCount")
    public record ReasonCount(String reason, int count) {}

    @Schema(name = "AdminReportDetailResponse", description = """
            신고 상세 — **신고 시점 스냅샷**이다. 원본이 수정·삭제돼도 이 값으로 검토한다.
            **신고자 신원은 응답에 없다.**""")
    public record ReportDetail(
            String reportId,
            String targetType,
            String targetId,
            String targetLabel,
            String reason,
            String status,
            String createdAt,
            @Schema(description = "접수자 남용 의심 — 불리언 하나뿐이고 상세는 이상탐지가 담당한다")
            boolean reporterFlagged,
            Snapshot snapshot,
            @Schema(description = "같은 대상의 다른 신고 — 반복 여부가 수준 판단을 가른다")
            List<Sibling> siblings,
            @Schema(description = "종결 정보. 미종결이면 null") Resolution resolution) {}

    @Schema(name = "AdminReportSnapshot", description = """
            접수 시점에 고정된 대상 콘텐츠·프로필·방 정보. **절대 갱신하지 않는다.**""")
    public record Snapshot(
            @Schema(description = "접수 시점 원본 payload") Object payload,
            @Schema(description = """
                    스냅샷에 담긴 이미지의 **단기 접근 주소**. 버킷을 공개로 열지 않고 보여주는 유일한 방법이다""")
            List<String> contentImageUrls,
            @Schema(description = "위 주소의 만료 시각 — 클라이언트가 만료를 알 수 있게 함께 내린다")
            String imageUrlsExpireAt) {}

    @Schema(name = "AdminReportSibling")
    public record Sibling(String reportId, String reason, String status, String createdAt) {}

    @Schema(name = "AdminReportResolution")
    public record Resolution(String status, String resolvedAt,
                             @Schema(description = "운영자 메모 — 유저에게는 어떤 경로로도 나가지 않는다")
                             String note,
                             String resolvedBy) {}

    @Schema(name = "AdminReportResolveRequest", description = """
            검토 결과 확정. **decision 은 둘뿐이다** — 제재와 폐쇄는 각자의 엔드포인트가
            근거 신고를 함께 종결시키므로 여기서 낼 결정이 아니다.""")
    public record ResolveRequest(
            @Schema(description = """
                    `NO_ACTION`(문제없음 종결) / `MODERATION_REJECT`(콘텐츠만 문제 → 거부 처리로 전환)""",
                    example = "NO_ACTION", requiredMode = Schema.RequiredMode.REQUIRED)
            String decision,

            @Schema(description = "판단 메모. 재검토 대응의 근거가 된다") String note,

            @Schema(description = """
                    같은 대상의 미검토 신고를 함께 종결할지. true 여도 **대상 목록은 서버가 다시 계산**한다 —
                    클라이언트가 id 목록을 만들면 그 사이 접수된 건이 조용히 빠진다""")
            boolean cascadeToTarget) {}

    @Schema(name = "AdminReportResolveResponse")
    public record ResolveResponse(
            ReportItem report,
            @Schema(description = "함께 종결된 건수(본 건 포함)") int resolvedCount) {}

    // ===== 제재 =====

    @Schema(name = "AdminSanctionRequest", description = """
            제재 집행. **사유 입력이 필수**이며 고지 알림에 그대로 실린다.
            `confirmationToken` 없이 보내면 428 과 함께 재확인 봉투가 내려온다.

            수단은 **3종 + `permanent` 플래그**다 — 운영자 제재 정책 § 5.4 가 영구 정지를 별도
            제재 종류로 두지 않는다고 못박고 있어, `LOCK` + `permanent=true` 가 영구 로그인 정지다.""")
    public record SanctionRequest(
            @Schema(description = "FEATURE_SUSPENSION / LOCK / BAN", example = "LOCK",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String type,

            @Schema(description = "true 면 해제일 없이 영구. `LOCK` 과 함께 오면 영구 로그인 정지다")
            Boolean permanent,

            @Schema(description = "기능 정지의 대상 — type 이 FEATURE_SUSPENSION 일 때만 쓴다")
            String featureCode,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String reasonCode,

            @Schema(description = "운영자 입력 사유 — **필수**. 고지 알림에 그대로 실린다",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String reasonText,

            @Schema(description = "REPORT / ANOMALY / DIRECT — **검토 근거 추적의 핵심**")
            String source,

            @Schema(description = "근거가 된 신고·이상탐지 ID") String sourceId,

            @Schema(description = "이 제재로 함께 종결할 신고 ID 목록 — 근거 신고를 큐에 남겨 두지 않는다")
            List<String> resolveReportIds,

            @Schema(description = "2단계 확인 토큰") String confirmationToken) {}

    @Schema(name = "AdminSanctionResponse")
    public record SanctionResponse(
            String sanctionId,
            String targetUserId,
            String type,
            boolean permanent,
            String accountStatus,
            String startsAt,
            String endsAt,
            @Schema(description = "필수(A) 고지 발행 시각 — null 이면 가드레일 위반이다")
            String notifiedAt,
            @Schema(description = "이 집행으로 함께 종결된 신고 수") int resolvedReportCount) {}

    @Schema(name = "AdminSanctionRevokeRequest")
    public record RevokeRequest(
            @Schema(description = "해제 사유 — **감사 로그에 남는다**", requiredMode = Schema.RequiredMode.REQUIRED)
            String reasonText) {}

    @Schema(name = "AdminSanctionListResponse", description = """
            전체 제재 이력. 유저 하위 경로만으로는 **최근 무엇이 집행됐는지**를 볼 수 없어 따로 둔다.""")
    public record SanctionListResponse(List<SanctionItem> items, String nextCursor) {}

    @Schema(name = "AdminSanctionItem", description = """
            **`active` 는 서버가 계산한다** — 진행 중 · 동결 · 영구의 세 경우를 클라이언트가 판단하면
            동결된 계정이 「제재 없음」으로 보인다.""")
    public record SanctionItem(
            String sanctionId,
            String userId,
            @Schema(description = "피제재자 닉네임") String nickname,
            @Schema(description = "AUTO / DISCRETIONARY — **합산하지 않는다**") String track,
            String type,
            @Schema(description = "해제일이 없는 제재인지") boolean permanent,
            String featureCode,
            String reasonCode,
            String reasonText,
            String source,
            String sourceId,
            String startsAt,
            @Schema(description = "해제 예정. 영구·동결이면 null") String endsAt,
            @Schema(description = "탈퇴로 동결된 잔여 초. 시간이 흘러도 줄지 않는다") Integer frozenRemainingSec,
            String revokedAt,
            @Schema(description = "재검토 1회 소진 여부") boolean appealUsed,
            String notifiedAt,
            @Schema(description = "서버 계산 — 진행 중이거나 동결이거나 영구면 true") boolean active) {}

    // ===== 유저 통합 뷰 =====

    @Schema(name = "AdminUserViewResponse", description = """
            판단 근거 통합 뷰. **자동 제재와 직권 제재를 별개 배열로** 내리며 합산하지 않는다 —
            성격이 달라 섞으면 재범 판정이 불공정해진다. 판단에 불필요한 항목은 넣지 않는다.""")
    public record UserView(
            String userId,
            String nickname,
            String accountStatus,
            String joinedAt,
            List<SanctionItem> adminSanctions,
            List<SanctionItem> autoSanctions,
            List<AnomalyItem> anomalies,
            @Schema(description = "이 유저를 대상으로 접수된 신고 건수") long reportCount,
            @Schema(description = "이 유저가 접수한 신고 건수 — 남용 판단의 분모") long reportedByCount,
            @Schema(description = "참여 중인 챌린지 수") long activeChallengeCount,
            @Schema(description = "미답변 CS 문의 수 — 제재 판단 중 대기 중인 문의가 있으면 함께 본다")
            long openInquiryCount) {}

    // ===== 이상탐지 =====

    @Schema(name = "AdminAnomalyResponse", description = """
            **탐지만으로는 제재하지 않는다** — 검토 대상 목록일 뿐이며 일괄 처리·자동 제재 경로를 두지 않는다.""")
    public record AnomalyResponse(List<AnomalyItem> items, String nextCursor) {}

    @Schema(name = "AdminAnomalyItem")
    public record AnomalyItem(
            String signalId,
            String signalType,
            String targetUserId,
            @Schema(description = "대상 닉네임") String targetNickname,
            int score,
            @Schema(description = """
                    **서버가 만든 판단 근거 문장.** 점수만으로는 사람이 판단할 수 없다 —
                    「30일간 신고 12건 중 8건이 문제없음 종결」 같은 문장이어야 다음 행동이 정해진다""")
            String summary,
            String detectedAt,
            String reviewedAt,
            String reviewerId,
            String reviewNote) {}

    @Schema(name = "AdminAnomalyReviewRequest")
    public record AnomalyReviewRequest(
            @Schema(description = "검토 메모 — 왜 넘겼는지가 남아야 같은 신호가 다시 올라왔을 때 반복하지 않는다")
            String note) {}

    // ===== 챌린지 =====

    @Schema(name = "AdminChallengeDetail", description = "폐쇄 판단 전 방 상태·인원 확인")
    public record ChallengeDetail(
            String challengeId,
            String title,
            String status,
            String ownerId,
            String ownerNickname,
            int activeMemberCount,
            int capacity,
            String startDate,
            String endDate,
            String createdAt,
            @Schema(description = "이 방에 접수된 신고 수") long reportCount,
            @Schema(description = "미검토 신고 수") long pendingReportCount,
            String imageUrl,
            String moderationStatus) {}

    @Schema(name = "AdminChallengeCloseRequest")
    public record CloseRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String reasonText,
            @Schema(description = "이 폐쇄로 함께 종결할 신고 ID 목록") List<String> resolveReportIds,
            String confirmationToken) {}

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

    @Schema(name = "AdminNoticeRequest", description = """
            점검·장애·약관·종료 공지. **푸시 통계 필드가 없다** — 공지는 알림함에만 적재되고
            푸시가 나가지 않는다(알림 테크 스펙 오픈 이슈 #8, 2026-09-07 확정). 이 속성을 운영
            토글로 두지 않는 이유는 하나다: 누가 켜면 2만 명에게 푸시가 나간다.""")
    public record NoticeRequest(
            @Schema(description = "MAINTENANCE / INCIDENT / TERMS / SHUTDOWN", example = "MAINTENANCE",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String kind,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String title,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String body,
            @Schema(description = "탭했을 때 갈 곳. 없으면 알림함에 머문다") String deepLink,
            @Schema(description = "예약 발행 시각. 없으면 즉시 팬아웃 대기로 들어간다") String scheduledAt,
            String confirmationToken) {}

    @Schema(name = "AdminNoticeResponse", description = """
            공지 원본만 저장하고 즉시 응답한다. **적재는 팬아웃 잡이 청크 단위로** 한다 —
            2만 행 INSERT 를 요청-응답 안에서 하면 커넥션을 오래 잡고 실패 시 전부 롤백된다.""")
    public record NoticeResponse(
            @Schema(description = "공지 id. 팬아웃 진행은 announcements 행에 남는다") String announcementId,
            String kind,
            String title,
            @Schema(description = "**예상** 수신자 수 — 실제 적재 수는 팬아웃이 끝나야 확정된다")
            int recipientCount,
            String scheduledAt,
            String publishedAt,
            @Schema(description = "실제 팬아웃 완료 시각. 대기 중이면 null") String fannedOutAt,
            String canceledAt) {}

    @Schema(name = "AdminNoticeListResponse", description = "발행 이력. **이미 적재된 공지는 회수되지 않는다.**")
    public record NoticeListResponse(List<NoticeResponse> items) {}

    // ===== 대시보드 =====

    @Schema(name = "AdminDashboardSummary", description = """
            모니터링 지표. 목표치의 원본은 앱 운영 정책 § 5.8 이고, **`guardrails` 는 백오피스 § 3 의
            「0건」 목표를 그대로 잰다** — 0이 아니면 화면이 지표가 아니라 **경고**로 띄운다.""")
    public record DashboardSummary(
            @Schema(description = "7d / 30d / 90d") String range,
            String from,
            String to,
            Guardrails guardrails,
            Reports reports,
            Inquiries inquiries,
            Sanctions sanctions,
            Anomalies anomalies,
            Members members,
            Challenges challenges) {}

    @Schema(name = "AdminDashboardGuardrails", description = """
            **전부 0이어야 하는 값들이다.** 하나라도 0이 아니면 지표가 아니라 사고다.""")
    public record Guardrails(
            @Schema(description = "고지 없이 집행된 직권 제재 — 알림 발행 경로 점검")
            long unnotifiedDiscretionarySanctions,
            @Schema(description = "감사 로그 없이 집행된 제재 — 재검토 근거가 사라진 건이다")
            long sanctionsWithoutAudit,
            @Schema(description = "검토 근거 없는 잠금·영구 정지 — 자동 승격 경로가 생겼다는 신호")
            long discretionaryWithoutSource,
            @Schema(description = "거부된 백오피스 접근 시도 — 급증이 우회 시도의 신호다")
            long deniedAdminAccess) {}

    @Schema(name = "AdminDashboardReports")
    public record Reports(
            @Schema(description = "미검토 적체. **50건 초과면 경고**(백엔드 7절)") long pending,
            @Schema(description = "가장 오래 기다린 미검토 건의 접수 시각") String oldestPendingAt,
            long resolvedInRange,
            long receivedInRange) {}

    @Schema(name = "AdminDashboardInquiries", description = """
            SLA 지표(§ 5.8) — 최초 응답 중앙값 목표 영업일 1일, 준수율 95%.
            여기서는 달력 기준 시간으로 재고 영업일 환산은 하지 않는다(§ 5.8 의 목표치 확정 전).""")
    public record Inquiries(
            @Schema(description = "미답변") long open,
            long receivedInRange,
            long answeredInRange,
            @Schema(description = "접수→답변 소요 시간 중앙값(시간). 답변 건이 없으면 null")
            Double medianResponseHours,
            @Schema(description = "분류별 접수 건수 — **유저가 처음 고른 분류**로 센다")
            List<CategoryCount> byCategory) {}

    @Schema(name = "AdminDashboardCategoryCount")
    public record CategoryCount(String category, long count) {}

    @Schema(name = "AdminDashboardSanctions")
    public record Sanctions(
            @Schema(description = "현재 활성 — 동결·영구를 포함한다") long active,
            @Schema(description = "기간 내 집행 — 직권") long discretionaryInRange,
            @Schema(description = "기간 내 집행 — 자동") long autoInRange,
            @Schema(description = "기간 내 해제") long revokedInRange) {}

    @Schema(name = "AdminDashboardAnomalies")
    public record Anomalies(long unreviewed, long detectedInRange) {}

    @Schema(name = "AdminDashboardMembers")
    public record Members(long total, long active, long suspended, long joinedInRange) {}

    @Schema(name = "AdminDashboardChallenges")
    public record Challenges(long active, long createdInRange) {}
}
