package com.ruleup.ruleup_backend.challenge.domain;

/** 익명/실명 (CH-10). ANONYMOUS면 상세·멤버 응답에서 닉네임/프로필 마스킹. */
public enum Anonymity {
    REAL, ANONYMOUS;

    /** 익명 챌린지면 닉네임을 첫 글자만 남기고 마스킹. 실명이면 그대로. (예: "성은개발자" → "성****") */
    public String maskNickname(String nickname) {
        if (this != ANONYMOUS || nickname == null || nickname.isEmpty()) return nickname;
        return nickname.charAt(0) + "*".repeat(Math.max(1, nickname.length() - 1));
    }

    public boolean isAnonymous() { return this == ANONYMOUS; }
}