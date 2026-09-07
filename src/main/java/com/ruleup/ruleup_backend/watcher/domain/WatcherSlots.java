package com.ruleup.ruleup_backend.watcher.domain;

/**
 * 감시자 슬롯 한도 — API 명세 「감시자 목록 조회」의 {@code slots} 계약.
 *
 * <p><b>표기용 숫자가 아니라 실제 한도다.</b> 화면이 「감시자 2/3」으로 그리는데 서버가 네 번째
 * 수락을 통과시키면 사용자가 본 사실과 서버 상태가 갈라진다. 그래서 발급과 수락 양쪽에서 센다.
 *
 * <p>세는 대상은 <b>살아 있는 관계뿐</b>이다. 미수락 초대는 자리를 잠그지 않는다 — 링크 세 장을
 * 뿌려 두고 아무도 수락하지 않으면 「3/3」인 채로 만료까지 7일을 기다려야 하고, 사용자가 그걸
 * 푸는 방법이 없다. 화면의 「감시자 2/3」이 가리키는 것도 실제 감시자 수다.
 *
 * <p>대신 <b>진짜 관문을 수락 시점에 둔다.</b> 발급 시점 검사는 자리가 없는데 링크를 만들어
 * 공유하게 두지 않으려는 안내일 뿐이고, 자리가 남아 있을 때 미리 받아 둔 토큰은 그 검사를
 * 통과해 나간다. 관계 행이 생기는 곳에서 세지 않으면 한도는 사실상 없는 것과 같다.
 *
 * <p>구독(subscribed)이 붙으면 {@code freeLimit} 이 {@code null}(무제한)로 나간다. 구독 도메인이
 * 아직 없어 지금은 항상 {@code false} 지만, 클라이언트가 {@code used/freeLimit} 을 그대로 그리면
 * 구독자에게 「2/null」이 뜨므로 계약에서 null 가능성을 먼저 못박아 둔다.
 */
public final class WatcherSlots {

    /** 무료 계정의 감시자 상한. */
    public static final int FREE_LIMIT = 3;

    private WatcherSlots() {}

    /** 구독자는 무제한이라 한도가 {@code null} 이다. */
    public static Integer freeLimitFor(boolean subscribed) {
        return subscribed ? null : FREE_LIMIT;
    }

    /** 한 자리라도 남았는지. 구독자는 언제나 남아 있다. */
    public static boolean hasRoom(int used, boolean subscribed) {
        Integer limit = freeLimitFor(subscribed);
        return limit == null || used < limit;
    }
}
