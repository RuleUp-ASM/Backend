package com.ruleup.ruleup_backend.challenge.guide;

import java.util.UUID;

/** 방의 인증 조건이 정해졌거나 바뀌었다 — 커밋 뒤 안내 문구를 만든다. */
public record VerificationGuideRequested(UUID challengeId) {}
