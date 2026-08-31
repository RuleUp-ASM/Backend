package com.ruleup.ruleup_backend.me.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 마이 탭 메인 일괄 조회(GET /me/home).
 *
 * <p><b>본인 화면</b>이므로 닉네임·사진은 심사 상태와 무관하게 입력값을 보여주고, 지금 타인에게
 * 어떻게 보이는지는 status 뱃지로 알린다(거부 상태면 직전 승인본).
 *
 * <p>구 {@code mannerTemperature} 는 티어 3종으로 대체됐고, {@code cheatCount} 는 폐기됐다
 * (2026-08-26). 부정행위 누적 임계값이 사라져 "잠금까지 남은 횟수"라는 개념이 없다 —
 * 검출 1회가 곧 해당 챌린지 강퇴·영구 차단이고, 계정 잠금은 운영자 직권 전용이다.
 */
@Schema(name = "MeHomeResponse", description = "마이 탭 메인. 본인 화면이라 닉네임·사진은 입력값 + 심사 상태 뱃지다.")
public record MeHomeResponse(

        @Schema(description = "본인이 정한 닉네임", example = "준혁이의 도전") String nickname,

        @Schema(description = "PENDING / APPROVED / REJECTED / CONFLICT", example = "APPROVED")
        String nicknameStatus,

        @Schema(description = "본인이 올린 사진. 없으면 null") String profileImageUrl,

        @Schema(description = "NONE / PENDING / APPROVED / REJECTED", example = "APPROVED")
        String profileImageStatus,

        @Schema(description = "실제 티어", example = "GOLD") String tier,

        @Schema(description = "누적 점수 0~2,000 (계정당 단일 축)", example = "370") long score,

        @Schema(description = "표시 티어 — 강등 유예 반영", example = "GOLD") String displayTier,

        @Schema(description = "진행 중 / 완주 / 이탈 챌린지 수") Counts counts,

        @Schema(description = "ACTIVE / LOCKED", example = "ACTIVE") String accountStatus,

        @Schema(description = "LOCKED 일 때만 — 잠금 사유와 해제일") LockInfo lockInfo) {

    @Schema(name = "MeHomeCounts")
    public record Counts(int inProgress, int completed, int left) {}

    @Schema(name = "MeHomeLockInfo")
    public record LockInfo(
            @Schema(description = "잠금 사유 코드", example = "ABUSE") String reason,
            @Schema(description = "해제 예정 시각. 영구 정지면 null") String unlockAt) {}
}
