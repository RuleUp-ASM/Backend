package com.ruleup.ruleup_backend.challenge.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 패널티 설정(challenges.penalties JSON) — 서버가 강제한다(챌린지 정책 §8.4).
 *  - score      : 점수 패널티. 자동 인증 방 = ON / 수동 방 = OFF (고정, 클라 값 무시)
 *  - groupShare : 챌린지 내 공유. 그룹 = ON / 솔로 = OFF (고정, 클라 값 무시)
 *  - watcher    : 감시자 패널티. 기본 off — 유일하게 사용자가 선택
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChallengePenalties(boolean score, boolean groupShare, boolean watcher) {

    /** 서버 강제 규칙으로 조립 — 선택 가능한 것은 watcher 뿐. */
    public static ChallengePenalties enforced(boolean autoVerification, boolean group, Boolean watcher) {
        return new ChallengePenalties(autoVerification, group, Boolean.TRUE.equals(watcher));
    }
}
