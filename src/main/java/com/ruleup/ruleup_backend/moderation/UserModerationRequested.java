package com.ruleup.ruleup_backend.moderation;

import java.util.UUID;

/**
 * "이 사용자의 닉네임/사진을 (재)검수해 달라"는 도메인 이벤트.
 * 가입·프로필 변경 트랜잭션이 커밋된 뒤(AFTER_COMMIT) 비동기로 처리된다.
 */
public record UserModerationRequested(UUID userId) {}
