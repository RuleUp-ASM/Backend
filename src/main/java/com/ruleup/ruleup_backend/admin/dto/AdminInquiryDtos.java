package com.ruleup.ruleup_backend.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 운영자 쪽 CS 계약 — 백오피스 공통 5-2-1 B.
 *
 * <p>백오피스 문서에 CS 가 아예 없었고 접수 규칙의 원본은 앱 운영 정책 § 5 다. 여기서 정의하는
 * 것은 <b>관리자 쪽 계약</b>뿐이며, 답변 등록이 곧 종결이고 재등록은 409 다.
 */
public final class AdminInquiryDtos {

    private AdminInquiryDtos() {}

    @Schema(name = "AdminInquiryQueueResponse", description = "문의 큐. 커서는 불투명 문자열이다.")
    public record QueueResponse(List<Item> items, String nextCursor, long totalCount) {}

    @Schema(name = "AdminInquiryItem")
    public record Item(
            String inquiryId,
            String userId,
            @Schema(description = "접수자 닉네임 — CS 는 신고와 달리 **신원을 가리지 않는다**. 계정 상태를 봐야 답할 수 있다")
            String nickname,
            @Schema(description = "현재 분류") String category,
            @Schema(description = "RECEIVED / ANSWERED") String status,
            @Schema(description = "목록용 본문 앞부분") String preview,
            String createdAt,
            String answeredAt,
            @Schema(description = "접수 후 경과 시간(시간 단위) — 미답변 건의 트리아지 기준")
            long waitingHours) {}

    @Schema(name = "AdminInquiryDetail", description = """
            상세. **분류별 1차 확인 항목**(§ 5.1)을 바로 볼 수 있게 계정 상태와 자동 첨부 값을 함께 내린다 —
            운영자가 문의 하나를 답하려고 유저 뷰를 다시 여는 왕복을 없앤다.""")
    public record Detail(
            String inquiryId,
            String userId,
            String nickname,
            @Schema(description = "계정 상태 — `신고·제재` 분류의 1차 확인 항목이다") String accountStatus,
            String category,
            @Schema(description = "**유저가 처음 고른 분류.** 변경 사실은 유저에게 노출하지 않는다")
            String originCategory,
            String status,
            String body,
            List<String> imageUrls,
            @Schema(description = "이미지 주소의 만료 시각") String imageUrlsExpireAt,
            @Schema(description = "자동 첨부 — 앱 버전") String appVersion,
            @Schema(description = "자동 첨부 — OS 버전") String osVersion,
            @Schema(description = "자동 첨부 — 기기 모델") String deviceModel,
            @Schema(description = "자동 첨부 — 최근 오류 로그 ID") String errorLogId,
            String createdAt,
            String answerText,
            String answeredAt,
            String answeredBy,
            @Schema(description = "이 유저의 활성 제재 — `신고·제재` 문의는 이 값이 곧 답변 재료다")
            List<AdminDtos.SanctionItem> activeSanctions) {}

    @Schema(name = "AdminInquiryAnswerRequest", description = """
            답변 등록 = **종결**이다. 재등록 경로가 없어 409 `ALREADY_ANSWERED` 로 막는다 —
            유저 화면이 열람 전용이라 정정 답변을 보낼 자리가 애초에 없다.""")
    public record AnswerRequest(
            @Schema(description = "유저에게 그대로 보이는 답변", requiredMode = Schema.RequiredMode.REQUIRED)
            String answerText) {}

    @Schema(name = "AdminInquiryCategoryRequest", description = """
            분류 변경. **변경 사실은 유저에게 노출하지 않는다** — 고른 게 틀렸다고 알릴 이유가 없다.
            원본 분류는 그대로 남아 「분류별 접수 비중」 지표를 오염시키지 않는다.""")
    public record CategoryRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String category) {}
}
