package com.ruleup.ruleup_backend.verification.dto;

/**
 * 이의 신청 제출. type=FAILURE(MVP), targetDate(실패가 확정된 일자), content 필수, imageUrl 선택.
 */
public record ObjectionSubmitRequest(String type, String targetDate, String content, String imageUrl) {}
