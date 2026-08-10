package com.ruleup.ruleup_backend.challenge.domain;

/**
 * 가입 거절 사유 (가입 API 명세 — 409 {@code JOIN_BLOCKED} 의 {@code error.reason}).
 *
 * <p>구 명세의 403 TIER_NOT_ELIGIBLE · 409 CHALLENGE_FULL 분리 표기는 폐기됐다.
 * 거절은 전부 <b>409 JOIN_BLOCKED + reason</b> 단일 형식이며, 상세 조회의
 * {@code joinBlockReason} 미리보기도 같은 enum 을 쓴다.
 */
public enum JoinBlockReason {
    /** 이미 이 챌린지의 ACTIVE 멤버. */
    ALREADY_JOINED,
    /** 종료된 챌린지 — 가입 개념 없음. */
    CHALLENGE_COMPLETED,
    /** 비공개 방은 초대 링크로만 입장 가능(직접 가입 불가). */
    PRIVATE_INVITE_ONLY,
    /** 자진 탈퇴 1주 / 강퇴 1주→2주→4주 배수 대기 중(강퇴 사유와 무관하게 동일 — 정책 §10.2). */
    REJOIN_COOLDOWN,
    /** 동시 참여 무료 3개 초과 (⚠️ BM 확정 대기). */
    FREE_LIMIT,
    /** 정원 마감. */
    FULL,
    /** 표시 티어가 minTier 미만. */
    TIER_GATE
}
