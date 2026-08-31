package com.ruleup.ruleup_backend.auth.dto;

import com.ruleup.ruleup_backend.score.domain.Tier;
import com.ruleup.ruleup_backend.score.domain.UserScoreSummary;
import com.ruleup.ruleup_backend.user.domain.NicknameStatus;
import com.ruleup.ruleup_backend.user.domain.User;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 로그인/가입/내 프로필 응답의 user 블록 — 카카오 로그인 API 계약(2026-08-03)과 동일 스키마.
 * - nickname: 본인 화면용 — 심사 중이면 입력값, 거부면 직전 승인본(없으면 임시 닉네임)
 * - tier/score/displayTier: user_score_summaries 기준 (가입 직후 BRONZE 10)
 * - accountStatus: ACTIVE/LOCKED (BANNED는 403으로 응답 자체가 없음)
 * - lockInfo: LOCKED일 때만 { reason, unlockAt } — 처벌 도메인 확정 전까지 사유·해제일은 미정
 */
@Schema(name = "UserResponse", description = """
        사용자 정보 블록. 로그인·가입·내 프로필 응답이 모두 같은 스키마를 쓴다.
        여기 담긴 nickname 은 '본인 화면용'이라 검수 결과와 무관하게 본인이 인지하는 값이 내려간다.""")
public record UserResponse(

        @Schema(description = "사용자 ID (UUID)", example = "0f7a3c1e-2b9d-4f6a-8c11-5d2e7b4a9c03",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String id,

        @Schema(description = """
                본인 화면에 표시할 닉네임. 검수 중(PENDING)이면 신청한 값,
                거부(REJECTED)면 직전 승인본(없으면 서버가 배정한 임시 닉네임)이 내려간다.""",
                example = "규칙왕", requiredMode = Schema.RequiredMode.REQUIRED)
        String nickname,

        @Schema(description = """
                닉네임 검수 상태.
                · PENDING — 검수 대기(기능 제한 없음)
                · APPROVED — 승인
                · REJECTED — 거부. 타인에게는 임시 닉네임이 보인다
                · CONFLICT — 복원 중 다른 사람이 선점. 재설정이 필요하다""",
                example = "PENDING",
                allowableValues = {"PENDING", "APPROVED", "REJECTED", "CONFLICT"})
        String nicknameStatus,

        @Schema(description = "프로필 사진 URL. 등록 전이거나 검수 통과 전이면 null.",
                example = "https://cdn.ruleup.app/profile/0f7a3c1e.jpg")
        String profileImageUrl,

        @Schema(description = "실제 티어. 가입 직후에는 BRONZE.", example = "BRONZE")
        String tier,

        @Schema(description = """
                누적 점수 0~2,000. 티어마다 0~99 로 끊지 않는 계정당 단일 축이라
                승급해도 초과 점수가 사라지지 않는다. 가입 직후에는 10.""", example = "10")
        long score,

        @Schema(description = "화면 표시용 티어. 승급 연출 등으로 실제 티어와 다를 수 있다.", example = "BRONZE")
        String displayTier,

        @Schema(description = "선택한 관심 카테고리 코드 목록", example = "[\"EXERCISE\",\"STUDY\"]")
        List<String> interestCategories,

        @Schema(description = "온보딩 완료 여부. 가입이 원자적이라 조회되는 사용자는 항상 true 다.", example = "true")
        Boolean onboardingCompleted,

        @Schema(description = """
                계정 상태. ACTIVE 또는 LOCKED.
                LOCKED 는 열람 전용이라 조회는 되지만 쓰기 요청이 403 ACCOUNT_LOCKED 로 막힌다(로그아웃·탈퇴는 허용).
                정지(BANNED)는 로그인·재가입이 모두 403 이라 이 값으로 내려오지 않는다.""",
                example = "ACTIVE", allowableValues = {"ACTIVE", "LOCKED"})
        String accountStatus,

        @Schema(description = "잠금 상세. accountStatus=LOCKED 일 때만 채워지고 그 외에는 null.")
        LockInfo lockInfo) {

    @Schema(name = "LockInfo", description = "계정 잠금 상세 (LOCKED 일 때만)")
    public record LockInfo(
            @Schema(description = "잠금 사유", example = "계정 잠금") String reason,
            @Schema(description = "잠금 해제 예정 시각(ISO-8601). 미정이면 null.", example = "2026-09-01T00:00:00Z")
            String unlockAt) {}

    public static UserResponse from(User user, UserScoreSummary summary) {
        Tier tier = (summary != null) ? summary.getActualTier() : Tier.UNRANKED;
        Tier displayTier = (summary != null) ? summary.getDisplayTier() : Tier.UNRANKED;
        // 티어 안에서 0~99 로 끊지 않는다 — 계정당 하나의 단일 축 0~2,000 이다(정책 §1.1, 2026-08-26).
        long score = (summary != null) ? summary.getTotalScore() : 0L;
        return new UserResponse(
                user.getId().toString(),
                selfDisplayNickname(user),
                user.getNicknameStatus().name(),
                user.getProfileImageUrl(),
                tier.name(), score, displayTier.name(),
                user.getInterestCategories(),
                true,                                   // 가입이 원자적이라 완료 사용자만 존재
                user.getStatus().name(),
                lockInfo(user));
    }

    /** 본인 화면용 닉네임 — REJECTED면 직전 승인본(없으면 임시 닉네임 = approvedNickname). */
    private static String selfDisplayNickname(User user) {
        return (user.getNicknameStatus() == NicknameStatus.REJECTED)
                ? user.getApprovedNickname() : user.getNickname();
    }

    /**
     * 제재 중이라는 사실만 알린다. 종류·사유·해제일은 {@code sanctions} 가 소유하므로
     * 여기서 조회하지 않고 <b>GET /api/v1/users/me/sanctions</b> 로 보낸다 — 로그인 응답마다
     * 제재 테이블을 읽으면 정상 사용자까지 비용을 물게 된다.
     */
    private static LockInfo lockInfo(User user) {
        if (!user.isSuspended()) return null;
        return new LockInfo("계정 제재", null);
    }
}
