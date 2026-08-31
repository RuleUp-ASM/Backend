package com.ruleup.ruleup_backend.user.domain;

/**
 * 계정 상태 <b>3종</b> — 온보딩 테크 스펙 5-3 · 5-6 · 부록 A.
 *
 * <p>구 {@code LOCKED}·{@code BANNED}·{@code DORMANT} 는 상태값에서 뺐다. 이유가 서로 다르다.
 * <ul>
 *   <li><b>정지의 종류와 기간은 {@code sanctions} 가 소유</b>한다. 게이트는 {@code SUSPENDED}
 *       일 때만 그 테이블을 읽으므로, 정상 사용자의 요청 비용은 이 값 한 번 조회로 끝난다.
 *       상태를 세분화하지 않고도 제재 종류를 분리할 수 있는 이유다.</li>
 *   <li><b>휴면은 상태가 아니다.</b> {@code user_activity.last_active_on} 으로 계산하며 로그인하면
 *       자동으로 해소된다. 전이를 기록해도 이를 소비하는 로직이 없다.</li>
 * </ul>
 */
public enum UserStatus {
    /** 정상 이용. */
    ACTIVE,
    /** 제재 중 — 차단 범위는 활성 {@code sanctions.type} 이 정한다(FEATURE_SUSPENSION / LOCK / BAN). */
    SUSPENDED,
    /** 소프트 탈퇴 — deleted_at 기록. 같은 소셜 계정으로 다시 오면 복원한다. */
    WITHDRAWN
}
