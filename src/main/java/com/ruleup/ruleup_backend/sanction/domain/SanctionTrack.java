package com.ruleup.ruleup_backend.sanction.domain;

/**
 * 제재 트랙 — 백오피스 공통 5-3.
 *
 * <p><b>합산하지 않는다.</b> 자동 제재와 직권 제재는 성격이 달라서 섞으면 재범 판정이 불공정해진다.
 * 마이페이지도 {@code auto}·{@code admin} 두 배열로 갈라 내린다.
 */
public enum SanctionTrack {
    /** 인증·티어 판정에서 자동으로 발생. 잠금·영구 정지는 이 트랙에 나타나면 가드레일 위반이다. */
    AUTO,
    /** 운영자 직권. API 응답에서는 {@code ADMIN} 으로 내려간다. */
    DISCRETIONARY;

    /** 클라이언트 계약값 — API 명세의 {@code track} 은 AUTO / ADMIN 이다. */
    public String apiValue() {
        return this == DISCRETIONARY ? "ADMIN" : name();
    }
}
