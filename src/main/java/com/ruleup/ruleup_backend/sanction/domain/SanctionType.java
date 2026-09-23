package com.ruleup.ruleup_backend.sanction.domain;

/**
 * 제재 종류 — 온보딩 테크 스펙 5-6 · 부록 A.
 *
 * <p>게이트는 {@code users.status = SUSPENDED}일 때만 이 값을 읽어 차단 범위를 정한다.
 * <b>LOCK 과 BAN 은 직권 전용</b>이라 {@link SanctionTrack#AUTO} 에 나타나면 가드레일 위반이다.
 */
public enum SanctionType {
    /** {@code featureCode} 에 적힌 기능만 403 ACCOUNT_SUSPENDED. 다른 기능은 정상 동작한다. */
    FEATURE_SUSPENSION,
    /** 열람 전용 — 조회는 되고 상태 변경만 403 ACCOUNT_LOCKED. 기본 1개월. */
    LOCK,
    /** 영구 정지 — 로그인 시점에 403 ACCOUNT_BANNED. {@code endsAt} 이 null 인 것이 곧 BAN 이다. */
    BAN;

    /** 해제일이 없는 제재인지. */
    public boolean isPermanent() {
        return this == BAN;
    }
}
