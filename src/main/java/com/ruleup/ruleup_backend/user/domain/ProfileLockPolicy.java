package com.ruleup.ruleup_backend.user.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 닉네임·사진 <b>통합</b> 변경 잠금 — 마이페이지 테크 스펙 5-4 · 프로필 편집 API 명세.
 *
 * <p>둘 중 하나라도 바꾸는 저장을 하면 그 시점부터 두 항목 모두 1개월간 변경할 수 없다.
 * 항목별로 따로 잠그면 닉네임 → 사진 → 닉네임 순으로 사실상 무제한 변경이 되기 때문이다.
 *
 * <p><b>같은 저장 세션</b>이라는 예외가 하나 있다. 사진 등록은 별도 API(업로드+등록)라, 사진과
 * 닉네임을 함께 바꾸려면 요청이 두 번 나간다. 첫 요청이 잠금을 걸어 버리면 두 번째 요청이 자기
 * 잠금에 막혀 동시 수정이 불가능해진다. 그래서 잠금이 시작된 직후 10분은 같은 저장으로 묶는다
 * (임의 구현 규칙 — 명세가 동시 수정 지원용으로 허용한 재량).
 *
 * <p>관심 분야는 이 정책의 대상이 아니다 — 자유 변경이다.
 */
public final class ProfileLockPolicy {

    /**
     * 잠금 기간은 "1개월"이다. 30일 고정이 아니라 달력 한 달이라 KST 달력으로 더한다 —
     * 사용자에게 안내되는 해제일이 "다음 달 같은 날"이어야 말이 되기 때문이다.
     */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 같은 저장 세션으로 묶어 주는 창 — 사진 등록 직후의 닉네임 변경이 여기 들어온다. */
    public static final Duration SAVE_SESSION = Duration.ofMinutes(10);

    private ProfileLockPolicy() {}

    /** 잠금 해제 시각(마지막 저장 +1개월). 저장한 적이 없으면 null. */
    public static Instant lockedUntil(Instant changedAt) {
        return changedAt == null ? null
                : changedAt.atZone(KST).plusMonths(1).toInstant();
    }

    /** 지금 잠겨 있는지. 같은 저장 세션 안이면 아직 잠긴 것으로 보지 않는다. */
    public static boolean isLocked(Instant changedAt, Instant now) {
        if (changedAt == null) return false;
        if (isSameSaveSession(changedAt, now)) return false;
        return now.isBefore(lockedUntil(changedAt));
    }

    /** 잠금이 방금 시작됐는지 — 같은 저장으로 묶어야 하는 구간. */
    public static boolean isSameSaveSession(Instant changedAt, Instant now) {
        return changedAt != null && now.isBefore(changedAt.plus(SAVE_SESSION));
    }
}
