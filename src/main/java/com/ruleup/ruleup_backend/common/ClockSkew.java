package com.ruleup.ruleup_backend.common;

import java.time.Duration;

/**
 * 앱 시계와 DB 시계를 맞대 볼 때 허용하는 오차.
 *
 * <h4>왜 필요한가</h4>
 * 같은 사건을 두 시계가 적는다. {@code challenge_join_events.joined_at} 은 DB 기본값이라 <b>DB 시계</b>로
 * 찍히고, {@code verification_permission_waits.first_observed_at} 은 애플리케이션이 만든 값이라
 * <b>앱 시계</b>로 찍힌다. 둘은 NTP 로 맞춰져 있어도 정확히 같지 않다 — 실제로 테스트에서 앱이 DB보다
 * 49ms 뒤처져, 방금 만든 대기가 「이전 멤버십 것」으로 오판돼 즉시 해소된 적이 있다.
 *
 * <h4>왜 오차를 허용해도 되는가</h4>
 * 이 비교가 가리려는 경계는 <b>멤버십이 갈리는 지점</b>이다. 나갔다 다시 들어오려면 재입장 대기
 * 7일을 채워야 하므로, 진짜 경계는 항상 며칠 단위로 벌어져 있다. 몇 초의 여유는 그 경계를 흐리지
 * 못하면서 시계 오차만 흡수한다.
 *
 * <p>근본 해법은 한쪽 시계로 통일하는 것이지만, {@code first_observed_at} 을 DB 시계로 덮으면
 * 「언제 그 공백을 봤는가」라는 사건의 의미가 사라진다. 그래서 값이 아니라 비교를 무디게 한다.
 */
public final class ClockSkew {

    /** 앱·DB 시계 차이로 허용하는 최대 오차. */
    public static final Duration TOLERANCE = Duration.ofSeconds(5);

    /** SQL {@code INTERVAL ? SECOND} 에 그대로 넣기 위한 초 단위 값. */
    public static final long TOLERANCE_SECONDS = TOLERANCE.toSeconds();

    private ClockSkew() {}
}
