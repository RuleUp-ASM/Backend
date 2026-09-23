package com.ruleup.ruleup_backend.verification.dto;

import java.util.List;

/**
 * PUT /api/v1/challenges/{challengeId}/my-location 요청 — 내 인증 장소(앵커) 교체.
 *
 * <p>보낸 목록으로 앵커 세트 <b>전체를 갈아끼운다</b>(부분 수정 아님). 최대 3개.
 * 반경은 서버 설정값이라 요청에 없다.
 */
public record MemberLocationRequest(List<AnchorDto> anchors) {}
