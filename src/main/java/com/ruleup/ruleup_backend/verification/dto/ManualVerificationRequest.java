package com.ruleup.ruleup_backend.verification.dto;

/**
 * 수동 인증 제출(§10). method=PHOTO/SELF_CHECK, targetDate 기본 오늘, imageUrl은 PHOTO 필수.
 *  - asFallback: true면 예비 폴백 규칙 적용(월3회·솔로 즉시 SUCCESS·그룹 승인, §10.2).
 *  - content: 폴백 제출 시 필수(사진 포함 글 혹은 글). 이의 제기와 형식 통일.
 */
public record ManualVerificationRequest(String method, String targetDate, String imageUrl,
                                        String content, Boolean asFallback) {
    public boolean fallback() { return Boolean.TRUE.equals(asFallback); }
}
