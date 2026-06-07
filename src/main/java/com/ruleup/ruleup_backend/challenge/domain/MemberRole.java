package com.ruleup.ruleup_backend.challenge.domain;

/** 멤버 역할. OWNER=생성자(수정/삭제/승인 권한), MEMBER=일반 참여자. */
public enum MemberRole {
    OWNER, MEMBER
}