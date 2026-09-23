package com.ruleup.ruleup_backend.challenge.dto;

import com.ruleup.ruleup_backend.challenge.draft.DraftView;

/**
 * POST /api/v1/challenges/draft 응답 — 경로 B(설명 입력, LLM 5-Step).
 * Step 1·2 차단은 에러가 아니라 result=FALLBACK 정상 응답(클라는 재입력 화면 복귀).
 */
public record DraftResponse(
        String result,       // OK / FALLBACK
        String draftId,      // 서버 발급 초안 ID(24시간 보관) — FALLBACK 이면 null
        String message,      // FALLBACK 안내 문구 — OK 면 null
        DraftView draft      // 초안 전 필드 — FALLBACK 이면 null
) {
    public static DraftResponse ok(String draftId, DraftView draft) {
        return new DraftResponse("OK", draftId, null, draft);
    }

    public static DraftResponse fallback(String message) {
        return new DraftResponse("FALLBACK", null, message, null);
    }
}
