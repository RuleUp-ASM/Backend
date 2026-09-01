package com.ruleup.ruleup_backend.challenge.explore.store;

import com.ruleup.ruleup_backend.challenge.explore.ExploreSort;

import java.util.UUID;

/**
 * 정렬 ZSET 의 멤버 인코딩.
 *
 * <h4>왜 score 를 쓰지 않는가</h4>
 * ZSET 의 score 는 double 하나뿐이라 <b>2·3중 정렬 키를 담을 수 없다.</b> 인기는
 * (참여 수 → 마지막 참여 시각 → id) 3중 키이고, 나머지 정렬도 id 동점 처리가 필요하다.
 * score 만 쓰면 동점 구간의 순서를 Redis 가 <b>멤버 사전순으로</b> 정하는데, 그건 MySQL 의
 * {@code ORDER BY ... , c.id DESC} 와 다르다. 커서 페이징에서 두 저장소의 순서가 어긋나면
 * <b>폴백 전환 때 방이 중복되거나 사라진다.</b>
 *
 * <h4>그래서 전체 키를 멤버에 박는다</h4>
 * score 는 전부 0 으로 두고 정렬은 {@code ZRANGEBYLEX} 에 맡긴다. 멤버는
 * <pre>  &lt;고정폭 정렬키&gt;:&lt;32자 id hex&gt;</pre>
 * 이며 <b>사전순 오름차순 = 원하는 정렬 순서</b>가 되도록 인코딩한다. 그러면 커서는 그냥
 * "직전 페이지 마지막 멤버 문자열"이고, 다음 페이지는 {@code (member} 부터 읽으면 된다 —
 * MySQL keyset 페이징과 <b>같은 의미</b>가 된다.
 *
 * <h4>내림차순과 고정폭</h4>
 * 값을 음수가 없는 long 으로 정규화한 뒤 16자리 hex 로 찍는다. 고정폭이라야 사전순과 수치순이
 * 일치한다("9" &gt; "10" 문제). 내림차순은 {@code Long.MAX_VALUE - v} 로 뒤집고, id 동점 처리도
 * 같은 방향이어야 하므로 내림차순일 때는 <b>id 의 각 바이트를 반전</b>해 찍는다.
 */
public final class SortKeyCodec {

    private SortKeyCodec() {}

    /** 값이 없는 방(시작 전·표본 미달 등)의 자리. 해당 정렬에서는 애초에 후보에서 빠진다. */
    private static final long ABSENT = 0L;

    /** 비율은 소수 넷째 자리까지 본다 — DECIMAL(5,2) 원본보다 넉넉하다. */
    private static final long RATE_SCALE = 10_000L;

    /**
     * 멤버 문자열을 만든다.
     *
     * @param primary   1차 정렬값을 정규화한 비음수 long
     * @param secondary 2차 정렬값(없으면 0)
     */
    public static String member(ExploreSort sort, long primary, long secondary, UUID id) {
        boolean asc = sort.ascending();
        StringBuilder sb = new StringBuilder(16 + 16 + 1 + 32);
        sb.append(fixed(primary, asc));
        if (sort.secondary() != null) sb.append(fixed(secondary, asc));
        return sb.append(':').append(idPart(id, asc)).toString();
    }

    /** 멤버에서 challengeId 를 되꺼낸다. */
    public static UUID idOf(String member, ExploreSort sort) {
        String idPart = member.substring(member.indexOf(':') + 1);
        return ExploreKeys.fromHex(sort.ascending() ? idPart : invertHex(idPart));
    }

    // ===== 값 정규화 — MySQL 컬럼과 같은 값을 같은 long 으로 만든다 =====

    public static long ofCount(Integer count) {
        return count == null ? ABSENT : Math.max(0, count);
    }

    /** epoch millis. NULL 시각은 가장 오래된 값으로 취급한다 — SQL 경로의 COALESCE(.., epoch) 와 같다. */
    public static long ofInstantMillis(Long millis) {
        return millis == null ? ABSENT : Math.max(0, millis);
    }

    /** 0.0~1.0 비율. NULL 은 후보에서 빠지므로 여기까지 오지 않는다. */
    public static long ofRate(Double rate) {
        if (rate == null) return ABSENT;
        return Math.max(0, Math.round(rate * RATE_SCALE));
    }

    /** epoch day. 마감 임박(오름차순)에서 쓴다. */
    public static long ofEpochDay(Long day) {
        // 1970 이전 날짜가 들어올 일은 없지만 음수는 인코딩이 깨지므로 바닥을 둔다.
        return day == null ? ABSENT : Math.max(0, day);
    }

    // ===== 인코딩 =====

    private static String fixed(long value, boolean ascending) {
        long v = ascending ? value : Long.MAX_VALUE - value;
        return String.format("%016x", v);
    }

    private static String idPart(UUID id, boolean ascending) {
        String hex = ExploreKeys.hex(id);
        return ascending ? hex : invertHex(hex);
    }

    /**
     * hex 문자열의 각 니블을 반전한다. 반전한 값의 사전순 오름차순은 원본의 사전순 내림차순과
     * 같으므로, {@code ORDER BY c.id DESC} 를 {@code ZRANGEBYLEX} 하나로 표현할 수 있다.
     */
    private static String invertHex(String hex) {
        StringBuilder sb = new StringBuilder(hex.length());
        for (int i = 0; i < hex.length(); i++) {
            sb.append(Character.forDigit(15 - Character.digit(hex.charAt(i), 16), 16));
        }
        return sb.toString();
    }
}
