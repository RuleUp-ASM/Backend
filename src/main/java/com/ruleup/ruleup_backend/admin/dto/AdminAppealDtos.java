package com.ruleup.ruleup_backend.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * 운영자 쪽 이의 내역 계약 — Backend #361.
 *
 * <p>이의는 <b>접수 = 인용</b>이라 상태(계류·기각)가 없다. 운영자가 여기서 하는 일은 판단이 아니라 확인이다 —
 * 남용 의심(이상탐지 {@code APPEAL_ABUSE})의 근거를 보고, 「제 이의가 반영됐나요」 문의에 답한다.
 */
public final class AdminAppealDtos {

    private AdminAppealDtos() {}

    @Schema(name = "AdminAppealQueueResponse", description = "이의 목록 — 최신순. 커서는 불투명 문자열이다.")
    public record QueueResponse(List<Item> items, String nextCursor,
                                @Schema(description = "필터에 맞는 전체 건수") long totalCount) {}

    @Schema(name = "AdminAppealItem")
    public record Item(
            String appealId,
            @Schema(description = "접수(= 인용) 시각") String acceptedAt,
            String userId,
            @Schema(description = "승인된 닉네임 — CS 대응이라 신원을 가리지 않는다") String nickname,
            String challengeId,
            @Schema(description = "방 제목. 방이 지워졌으면 null") String challengeTitle,
            String challengeCategory,
            @Schema(description = "귀속일(KST)") String targetDate,
            @Schema(description = "접수 당시 실패 사유 코드. V21(2026-10-29) 이전 접수는 null") String failureReason,
            @Schema(description = "사유 앞부분") String reasonPreview,
            boolean hasImage) {}

    @Schema(name = "AdminAppealDetail", description = """
            이의 한 건. 상세 열람은 사유 원문과 증빙 사진이 담긴 **개인정보 열람**이라 감사 로그에 `APPEAL_VIEW` 로 따로 남는다.""")
    public record Detail(
            String appealId,
            String acceptedAt,
            String userId,
            String nickname,
            @Schema(description = "ACTIVE / SUSPENDED / WITHDRAWN") String accountStatus,
            String challengeId,
            String challengeTitle,
            String challengeCategory,
            String targetDate,
            @Schema(description = "이의 대상 인증(= 앱의 verificationId)") String verificationId,
            @Schema(description = "사유 전문") String reason,
            @Schema(description = "증빙 사진 단기 접근 주소(없으면 빈 배열)") List<String> imageUrls,
            @Schema(description = "사진 주소 만료 시각 — 지나면 상세를 다시 불러 새 주소를 받는다") String imageUrlsExpireAt,
            @Schema(description = "이의 전 실패 설명. V21(2026-10-29) 이전 접수는 null") Failure failure,
            @Schema(description = "같은 회원이 최근 30일 동안 낸 이의 수(이 건 포함)") long recentAppealCount30d) {}

    @Schema(name = "AdminAppealFailure", description = "유저가 신청 화면에서 본 실패 설명 — 접수 당시 값으로 고정돼 있다")
    public record Failure(
            String reasonCode,
            @Schema(description = "판정 근거 한 줄") String summary,
            @Schema(description = "기준값") Map<String, Object> expected,
            @Schema(description = "실제값") Map<String, Object> actual) {}
}
