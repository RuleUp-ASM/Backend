package com.ruleup.ruleup_backend.challenge.domain;

/**
 * 멤버 역할 (생성 및 라이프사이클 스펙 §7).
 *  - OWNER  : 방장. 운영 권한을 가진다.
 *  - MEMBER : 일반 참여자.
 * 공동 관리자(MANAGER)는 폐기됐으며 DB도 이 두 값만 허용한다.
 */
public enum MemberRole {
    OWNER, MEMBER
}
