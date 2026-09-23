package com.ruleup.ruleup_backend.verification.domain;

/**
 * 인증 설정에서 판정 방향(목표 달성형 / 규칙 지키기형)을 꺼낸다.
 * sync·확정 배치·조회가 모두 같은 값을 봐야 해서 한곳에 둔다.
 */
public final class VerificationPolarity {

    private VerificationPolarity() {}

    public static Polarity of(VerificationConfig config) {
        VerificationMethod method = (config != null) ? config.primaryMethod() : null;
        if (method == null) return Polarity.ACHIEVEMENT;
        Polarity declared = switch (method) {
            case WAKE -> (config.wake() != null) ? config.wake().polarity() : null;
            case SCREEN_TIME -> (config.screenTime() != null) ? config.screenTime().polarity() : null;
            case GPS_PRESENCE, GPS_DISTANCE -> (config.gps() != null) ? config.gps().polarity() : null;
            case HEALTH -> (config.health() != null) ? config.health().polarity() : null;
            case SLEEP -> (config.sleep() != null) ? config.sleep().polarity() : null;
            default -> null;
        };
        return (declared != null) ? declared : Polarity.ACHIEVEMENT;
    }
}
