package com.ruleup.ruleup_backend.inquiry.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 유저 쪽 CS 문의 계약 — 앱 운영 정책 § 5. 관리자 쪽 계약은 {@code AdminDtos} 에 있다. */
public final class InquiryDtos {

    private InquiryDtos() {}

    @Schema(name = "InquiryCreateRequest", description = """
            문의 접수. 입력은 **카테고리 1개 + 본문**의 2단계이며 그 밖의 폼 항목을 두지 않는다(§ 5).

            자동 첨부(앱·OS 버전 · 기기 모델 · 오류 로그 ID)는 **고지 후 필수라 토글이 없다**.
            없으면 없는 대로 받는다 — 문의를 막을 값이 아니다.""")
    public record CreateRequest(

            @Schema(description = """
                    VERIFICATION(인증·판정) / DEVICE_PERMISSION(권한·기기 연동) /
                    CHALLENGE_GROUP(챌린지·그룹) / ACCOUNT_LOGIN(계정·로그인) /
                    REPORT_SANCTION(신고·제재) / ERROR_ETC(오류·제안·기타)""",
                    example = "ERROR_ETC", requiredMode = Schema.RequiredMode.REQUIRED)
            String category,

            @Schema(description = "**10자 이상 1,000자 이하**", requiredMode = Schema.RequiredMode.REQUIRED)
            String body,

            @Schema(description = "선택 · 최대 3장. 업로드 API 가 준 `/files/{파일명}` 주소를 그대로 보낸다.")
            List<String> imageUrls,

            @Schema(description = "자동 첨부 — 앱 버전", example = "1.2.0") String appVersion,
            @Schema(description = "자동 첨부 — OS 버전", example = "Android 15") String osVersion,
            @Schema(description = "자동 첨부 — 기기 모델", example = "SM-S928N") String deviceModel,
            @Schema(description = "자동 첨부 — 최근 오류 로그 ID") String errorLogId) {}

    @Schema(name = "InquiryCreateResponse", description = "접수 완료 시트에 그대로 쓰는 값이다 — 접수번호와 접수 시각.")
    public record CreateResponse(
            @Schema(description = "접수번호") String inquiryId,
            @Schema(description = "RECEIVED") String status,
            String createdAt) {}

    @Schema(name = "InquiryListResponse", description = "내 문의 내역. 상태 칩과 **새 답변 배지**를 이 값으로 그린다.")
    public record ListResponse(List<Item> items) {}

    @Schema(name = "InquiryListItem")
    public record Item(
            String inquiryId,
            @Schema(description = "현재 분류 — **운영자가 바꿨더라도 그 사실은 알리지 않는다**") String category,
            @Schema(description = "RECEIVED / ANSWERED") String status,
            @Schema(description = "목록용 본문 앞부분") String preview,
            String createdAt,
            @Schema(description = "답변 시각. 미답변이면 null") String answeredAt) {}

    @Schema(name = "InquiryDetailResponse", description = """
            문의 상세는 **열람 전용**이다(§ 5.5). 답변 이후 이 스레드에 글을 추가하는 경로를 두지 않고,
            화면 하단에는 새 문의 작성 진입만 둔다.""")
    public record Detail(
            String inquiryId,
            String category,
            String status,
            String body,
            List<String> imageUrls,
            String createdAt,
            @Schema(description = "운영팀 답변. 미답변이면 null") String answerText,
            String answeredAt) {}
}
