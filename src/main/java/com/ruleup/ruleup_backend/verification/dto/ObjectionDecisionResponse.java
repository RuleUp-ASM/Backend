package com.ruleup.ruleup_backend.verification.dto;

/**
 * 이의 제기 처리 응답(§8.7).
 *  - resultStatus: SUCCESS(승인) / FAILED(기각·확정)
 *  - verifiedVia : 승인 시 OBJECTION, 기각 시 null
 */
public record ObjectionDecisionResponse(String objectionId, String status, String targetDate,
                                        String resultStatus, String verifiedVia) {}
