package com.ruleup.ruleup_backend.verification.dto;

import java.util.List;

/**
 * 방장/공동 관리자 처리 대기함(pending-reviews). 폴백 수동 인증(PENDING_APPROVAL)과 이의 제기(PENDING) 통합.
 * kind에 따라 처리 API가 갈린다: FALLBACK→/verifications/{id}/approval, OBJECTION→/objections/{id}/decision.
 */
public record PendingReviewsResponse(String challengeId, int pendingCount, List<Item> items) {

    public record Item(
            String kind,          // FALLBACK / OBJECTION
            String id,            // verificationId 또는 objectionId
            String userId,
            String nickname,      // 익명 챌린지는 마스킹
            String targetDate,
            String content,
            String imageUrl,
            String submittedAt,   // ISO
            String deadline       // OBJECTION 이면 이의 제기 창 마감, FALLBACK 은 null
    ) {}
}
