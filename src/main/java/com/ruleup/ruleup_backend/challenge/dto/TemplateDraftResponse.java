package com.ruleup.ruleup_backend.challenge.dto;

import com.ruleup.ruleup_backend.challenge.draft.DraftView;

/**
 * POST /api/v1/challenges/recommendation/by-template 응답 — 경로 A(추천 탭, LLM 미경유).
 * draft 는 경로 B(draft API)와 완전히 같은 스키마 — 클라는 같은 확인 화면을 재사용한다.
 */
public record TemplateDraftResponse(
        String draftId,      // 서버 발급 초안 ID(origin=TEMPLATE, 24시간 보관)
        DraftView draft
) {}
