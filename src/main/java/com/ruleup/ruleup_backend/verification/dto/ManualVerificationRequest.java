package com.ruleup.ruleup_backend.verification.dto;

/**
 * §11.6 수동 인증 제출. method=PHOTO/SELF_CHECK, targetDate 기본 오늘, imageUrl은 PHOTO 필수.
 *  - asFallback: true면 예비 폴백 규칙 적용(주1회·잠정성공·이의윈도우, §9.2).
 */
public record ManualVerificationRequest(String method, String targetDate, String imageUrl, Boolean asFallback) {
    public boolean fallback() { return Boolean.TRUE.equals(asFallback); }
}
