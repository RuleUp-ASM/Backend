package com.ruleup.ruleup_backend.challenge.moderation;

import java.util.UUID;

/**
 * "이 챌린지의 이름을 (재)검수해 달라"는 도메인 이벤트 (CLAUDE.md §5.1).
 * 생성·이름/이미지 변경 트랜잭션이 커밋된 뒤(AFTER_COMMIT) 비동기로 처리된다
 * (가입/생성 핫패스에 LLM 외부 호출을 두지 않기 위함 — §3 트랜잭션 경계).
 */
public record ChallengeModerationRequested(UUID challengeId) {}
