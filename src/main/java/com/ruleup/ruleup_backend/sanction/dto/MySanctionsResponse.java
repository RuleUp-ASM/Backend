package com.ruleup.ruleup_backend.sanction.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * GET /api/v1/users/me/sanctions 응답 — 마이페이지 공통 5-2 #10.
 *
 * <p>세 가지가 이 계약의 성격을 결정한다.
 * <ul>
 *   <li><b>자동·직권을 별개 배열로</b> 내리고 합산하지 않는다 — 누적 카운트로 승격하는 경로가 없다</li>
 *   <li><b>열람 전용</b>이다 — 이의 제기 버튼을 두지 않는다. 강퇴는 CS 문의, 직권 제재는
 *       CS 경유 재검토 1회로만 다툰다</li>
 *   <li><b>잠금 상태에서도 열려야 한다</b> — 사유와 해제일을 볼 수 없으면 상황을 알 방법이 없다</li>
 * </ul>
 */
@Schema(name = "MySanctionsResponse", description = "내 제재 통지·이력 (열람 전용)")
public record MySanctionsResponse(

        @Schema(description = "계정 상태", example = "SUSPENDED",
                allowableValues = {"ACTIVE", "SUSPENDED", "WITHDRAWN"})
        String accountStatus,

        @Schema(description = "현재 효력이 있는 제재. 없으면 null 이고 accountStatus 는 ACTIVE 다.")
        Active activeSanction,

        @Schema(description = "직권 제재 이력 — 운영자 검토를 거친 것", requiredMode = Schema.RequiredMode.REQUIRED)
        List<AdminItem> admin,

        @Schema(description = "자동 제재 이력 — 인증·티어 판정에서 발생한 챌린지 강퇴",
                requiredMode = Schema.RequiredMode.REQUIRED)
        List<AutoItem> auto) {

    @Schema(name = "ActiveSanction")
    public record Active(
            String sanctionId,
            @Schema(description = "AUTO / ADMIN", example = "ADMIN") String track,
            @Schema(description = "FEATURE_SUSPENSION / LOCK / BAN", example = "LOCK") String type,
            @Schema(description = "기능 정지의 대상. 그 외에는 null.") String featureCode,
            String reasonCode,
            @Schema(description = "운영자 입력 사유. 모더레이션 거부 사유는 상세를 담지 않는다.")
            String reasonText,
            String startsAt,
            @Schema(description = "해제 예정 시각. **영구 정지는 null** 이다.") String endsAt,
            @Schema(description = "CS 경유 재검토를 아직 쓸 수 있는지 — 제재당 1회") boolean reviewRequestable) {}

    @Schema(name = "AdminSanctionItem")
    public record AdminItem(
            String sanctionId,
            String type,
            String featureCode,
            String reasonCode,
            String startsAt,
            String endsAt,
            @Schema(description = "NONE / USED", example = "NONE") String reviewStatus) {}

    @Schema(name = "AutoSanctionItem", description = "챌린지 강퇴 — 계정 제재가 아니라 방 단위 집행이다")
    public record AutoItem(
            String challengeId,
            String challengeTitle,
            @Schema(description = "CHEAT_DETECTED / CONSECUTIVE_FAILURE / PERMISSION_MISSING")
            String reasonCode,
            @Schema(description = "부정행위 검출이면 true — 해당 챌린지 영구 차단이라 재입장이 없다")
            boolean permanent,
            @Schema(description = "재입장 가능 시각. permanent 면 null.") String rejoinAvailableAt,
            String occurredAt) {}
}
