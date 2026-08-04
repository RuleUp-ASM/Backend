package com.ruleup.ruleup_backend.auth.dto;

import com.ruleup.ruleup_backend.score.domain.Tier;
import com.ruleup.ruleup_backend.score.domain.UserScoreSummary;
import com.ruleup.ruleup_backend.user.domain.NicknameStatus;
import com.ruleup.ruleup_backend.user.domain.User;

import java.util.List;

/**
 * 로그인/가입/내 프로필 응답의 user 블록 — 카카오 로그인 API 계약(2026-08-03)과 동일 스키마.
 * - nickname: 본인 화면용 — 심사 중이면 입력값, 거부면 직전 승인본(없으면 임시 닉네임)
 * - tier/score/displayTier: user_score_summaries 기준 (가입 직후 BRONZE 10)
 * - accountStatus: ACTIVE/LOCKED (BANNED는 403으로 응답 자체가 없음)
 * - lockInfo: LOCKED일 때만 { reason, unlockAt } — 처벌 도메인 확정 전까지 사유·해제일은 미정
 */
public record UserResponse(
        String id, String nickname, String nicknameStatus, String profileImageUrl,
        String tier, Integer score, String displayTier,
        List<String> interestCategories, Boolean onboardingCompleted,
        String accountStatus, LockInfo lockInfo) {

    public record LockInfo(String reason, String unlockAt) {}

    public static UserResponse from(User user, UserScoreSummary summary) {
        Tier tier = (summary != null) ? summary.getActualTier() : Tier.UNRANKED;
        Tier displayTier = (summary != null) ? summary.getDisplayTier() : Tier.UNRANKED;
        int score = (summary != null) ? summary.scoreInTier() : 0;
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

    private static LockInfo lockInfo(User user) {
        if (!user.isLocked()) return null;
        return new LockInfo("계정 잠금", null);   // 사유·해제일은 처벌 도메인 스펙에서 확정
    }
}
