package com.ruleup.ruleup_backend.challenge.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * 강퇴 후 재입장 대기 백오프 (제재 정책 §4.3).
 *
 * <p>대기 기간은 <b>1주 → 2주 → 4주 → 8주 …</b> 로 강퇴할 때마다 두 배가 되며,
 * 강퇴 사유(신고 누적 · 연속 실패 · 권한 미허용 · 방장 재량 · 부정행위)와 <b>무관하게 동일 적용</b>한다
 * (정책 §10.2 — 사유별 예외를 두지 않으므로 영구 차단 경로도 없다).
 * 회차는 해당 챌린지 단위로 센다.
 *
 * <p>방장 재량 강퇴(RoomAdminService)와 신고 누적 자동 강퇴(ReportReviewService)가 같은 값을 써야 하므로
 * 계산을 여기 한곳에 둔다.
 */
public final class RejoinBackoff {

    private RejoinBackoff() {}

    /** 1회차 대기 기간. 이후 회차마다 두 배. */
    private static final int BASE_WEEKS = 1;

    /** 지수 폭주 방지 상한(약 2년). 정책에 종료 회차가 없어 방어적으로만 둔다. */
    private static final int MAX_DOUBLINGS = 7;   // 1·2·4·8·16·32·64·128주

    /**
     * 이번 강퇴의 재입장 가능 시각.
     *
     * @param kickedAt          강퇴 시각
     * @param previousKickCount 이 챌린지에서 <b>이전까지</b> 강퇴된 횟수(0이면 첫 강퇴 → 1주)
     */
    public static Instant availableAt(Instant kickedAt, int previousKickCount) {
        return kickedAt.plus(Duration.ofDays(7L * weeks(previousKickCount)));
    }

    /** 회차별 대기 주 수: 1, 2, 4, 8 … */
    public static long weeks(int previousKickCount) {
        int doublings = Math.min(Math.max(previousKickCount, 0), MAX_DOUBLINGS);
        return (long) BASE_WEEKS << doublings;
    }
}
