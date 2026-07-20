package com.ruleup.ruleup_backend.verification.dto;

/**
 * 이의 제기 제출(§8.7). type=FAILURE(MVP), targetDate(잠정 실패 일자), content 필수, imageUrl 선택.
 */
public record ObjectionSubmitRequest(String type, String targetDate, String content, String imageUrl) {}
