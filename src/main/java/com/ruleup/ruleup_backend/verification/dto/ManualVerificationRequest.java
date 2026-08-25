package com.ruleup.ruleup_backend.verification.dto;

/**
 * POST /api/v1/challenges/{challengeId}/verifications 요청 — 수동 인증(자체 체크) 제출.
 *
 * <p>수동 방에서만 쓴다. 자동 방의 수동 폴백은 폐기됐고, 자동 방의 실패 구제는 이의 제기가 담당한다.
 * 별도 부정 방지 장치 없이 제출 즉시 인정된다(치팅 가능성은 정책적으로 수용).
 *
 * @param targetDate 귀속일(YYYY-MM-DD). 오늘만 허용 — 날짜가 지나면 체크 불가. 생략하면 오늘
 * @param note       메모(기록용, 검증 없음)
 */
public record ManualVerificationRequest(String targetDate, String note) {}
