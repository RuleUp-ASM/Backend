package com.ruleup.ruleup_backend.challenge.dto;

import com.ruleup.ruleup_backend.challenge.draft.DraftView;

/**
 * 템플릿 복제 응답 — clone API 명세.
 *
 * @param draftId           서버 발급 초안 ID(origin=CLONE, 24시간 보관). 최종 생성 요청에 그대로 전달한다
 * @param sourceChallengeId 원본 참조 — 표시용 참고값일 뿐이다. 출처는 서버가 draft 행에 기록하며
 *                          생성 요청에서 클라이언트가 지정할 수 없다(출처 노출 여부는 정책 미확정)
 * @param draft             생성 모듈의 draft 와 <b>동일 스키마</b> — 확인 화면·생성 API 를 그대로 재사용한다
 */
public record CloneResponse(String draftId, String sourceChallengeId, DraftView draft) {}
