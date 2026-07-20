package com.ruleup.ruleup_backend.challenge.domain;

/**
 * 멤버 역할 (생성 및 라이프사이클 스펙 §7).
 *  - OWNER   : 생성자. 수정/삭제/인원 상한/임명·해제/위임 권한. 항상 정확히 1명(불변식).
 *  - MANAGER : 공동 관리자. 공지 + 이의 제기 처리로 한정. 위임 대상.
 *  - MEMBER  : 일반 참여자.
 * DB ENUM('OWNER','MANAGER','MEMBER')와 1:1.
 */
public enum MemberRole {
    OWNER, MANAGER, MEMBER
}
