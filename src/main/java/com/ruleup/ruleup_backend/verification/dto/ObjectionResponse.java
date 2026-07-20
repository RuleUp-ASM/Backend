package com.ruleup.ruleup_backend.verification.dto;

/** 이의 제기 제출 응답(§8.7). */
public record ObjectionResponse(String objectionId, String type, String status, String deadline) {}
