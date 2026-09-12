package com.ruleup.ruleup_backend.score.domain;

/**
 * 연속 실패 경고를 낼 시점 — 순수 함수다.
 *
 * <p>값은 {@link ChallengeStreak} 이 원본으로 적어 둔 <b>2사이클 경고 · 3사이클 강퇴</b>에서 온다.
 * 경고는 강퇴 직전 고지라 <b>정확히 한 번</b> 울려야 한다.
 *
 * <ul>
 *   <li>1사이클에 울리면 — 한 주 미달마다 경고가 나가 의미가 닳는다.</li>
 *   <li>3사이클 이후에도 계속 울리면 — 이미 집행 단계라 「곧 강퇴」가 사실과 어긋난다.</li>
 * </ul>
 *
 * <p>판정을 서비스에 묻지 않고 뗀 이유가 이것이다. 경계가 한 칸만 밀려도 예외가 아니라
 * <b>매 사이클 반복되는 잘못된 알림</b>이 되는데, 그 사실은 사용자가 문의를 넣어야 드러난다.
 */
public final class StreakWarning {

    /** 경고 사이클. 강퇴는 그 다음 사이클(3)이고 집행은 방 내부 모듈 몫이다. */
    public static final int WARN_AT = 2;

    private StreakWarning() {}

    /**
     * 이번 사이클 마감으로 경고를 내보내야 하는가.
     *
     * @param result       이번 사이클 판정 — 실패가 아니면 연속 기록이 끊겨 경고 대상이 아니다
     * @param failureStreak {@code apply()} <b>이후</b>의 연속 실패 수
     */
    public static boolean shouldWarn(CycleResult result, int failureStreak) {
        return result == CycleResult.FAILURE && failureStreak == WARN_AT;
    }
}
