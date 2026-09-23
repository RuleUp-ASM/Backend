package com.ruleup.ruleup_backend.score.domain;

/**
 * 점수가 움직인 뒤 <b>티어 고지를 보낼 것인가</b> — 순수 함수다.
 *
 * <p>기준은 <b>표시 티어</b>다. 실제 티어로 판정하면 강등 유예 구간에서 "강등됐다"고 알리는데
 * 화면의 티어는 그대로라, 사용자가 보는 것과 알림이 어긋난다. 유예는 그 어긋남을 막으려고
 * 존재하는 장치이므로 고지도 같은 축을 따라야 한다.
 *
 * <p>판정을 서비스 안에 묻지 않고 떼어 둔 이유는 규칙이 조용히 틀리기 쉬워서다 — 방향이 뒤집히거나
 * 경계 근접이 승급 직후에도 계속 울리면, 그건 예외가 아니라 <b>매주 반복되는 잘못된 알림</b>이 된다.
 */
public record TierNotice(Kind kind, String direction) {

    public enum Kind {
        /** 보낼 것이 없다 — 대부분의 점수 변동이 여기다. */
        NONE,
        /** 표시 티어가 실제로 바뀌었다. */
        CHANGED,
        /** 아직 안 바뀌었지만 경계가 코앞이다. */
        BOUNDARY_NEAR
    }

    public static final String UP = "UP";
    public static final String DOWN = "DOWN";

    /**
     * 경계 근접으로 볼 점수 폭.
     *
     * <p><b>스펙에 값이 없어 여기서 정한다.</b> 티어 간격이 최소 100점이고 강등 유예가 20점이라,
     * 그보다 좁은 10점이어야 "곧 닿는다"가 실제로 곧이다. 너무 넓게 잡으면 밴드 한가운데서도
     * 울리고, 너무 좁게 잡으면 한 번의 감점으로 경계를 건너뛰어 예고가 무의미해진다.
     *
     * <p>반복 스팸은 이 값이 아니라 레지스트리의 <b>1주 억제</b>가 막는다.
     */
    public static final int BOUNDARY_POINTS = 10;

    private static final TierNotice NONE_NOTICE = new TierNotice(Kind.NONE, null);

    /**
     * @param before     점수 반영 <b>전</b>의 표시 티어
     * @param after      점수 반영 <b>후</b>의 표시 티어
     * @param scoreAfter 반영 후 누적 점수
     */
    public static TierNotice of(Tier before, Tier after, long scoreAfter) {
        // 요약이 아직 없던 상태(UNRANKED)에서의 첫 부여는 승급이 아니다 — 가입이다.
        if (before == null || after == null
                || before == Tier.UNRANKED || after == Tier.UNRANKED) return NONE_NOTICE;

        if (after != before) {
            return new TierNotice(Kind.CHANGED, after.ordinal() > before.ordinal() ? UP : DOWN);
        }

        // 강등을 먼저 본다. 둘 다 해당할 수는 없지만(밴드 간격 ≥ 100 > 2×10),
        // 겹치는 값이 생기더라도 잃는 쪽을 먼저 알리는 편이 사용자에게 유용하다.
        if (TierBands.hasDemotion(after)) {
            long margin = scoreAfter - TierBands.demoteAt(after);
            if (margin > 0 && margin <= BOUNDARY_POINTS) {
                return new TierNotice(Kind.BOUNDARY_NEAR, DOWN);
            }
        }

        Tier next = TierBands.next(after);
        if (next != null) {
            long remaining = TierBands.pointsToPromote(scoreAfter, next);
            // 0 이면 이미 넘어선 것이라 CHANGED 로 잡혔어야 한다 — 예고할 자리가 아니다.
            if (remaining > 0 && remaining <= BOUNDARY_POINTS) {
                return new TierNotice(Kind.BOUNDARY_NEAR, UP);
            }
        }
        return NONE_NOTICE;
    }

    public boolean isNone() {
        return kind == Kind.NONE;
    }
}
