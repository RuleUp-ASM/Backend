package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.user.domain.User;

/**
 * sync 주기(flushIntervalSec) 산정 정책(§6 제어 모델). 기기 스펙 기반으로 결정하고, 실행은 클라가 한다.
 *
 * <p>MVP 규칙(보수적):
 * <ul>
 *   <li>기본 = 30분(1800s).</li>
 *   <li>취약 기기(lowRam 또는 오래된 OS)는 완화된 주기(1시간) — 배터리/발열 보호.</li>
 * </ul>
 * "좋은 기기는 더 짧은 주기"는 배터리 트레이드오프가 있어 MVP에선 기본값을 유지하고, 실데이터 확보 후 튜닝한다.
 * 서버가 관리하는 정책은 flushIntervalSec 하나뿐(§0.3 조정 사항).
 */
public final class FlushIntervalPolicy {

    /** 기본 sync 주기 = 30분. */
    public static final int DEFAULT_SEC = 1800;
    /** 취약 기기 완화 주기 = 1시간. */
    public static final int RELAXED_SEC = 3600;
    /** 오래된 OS 기준(Android 8.0 = API 26 미만이면 백그라운드 제약이 커 완화). */
    private static final int LEGACY_SDK_INT = 26;

    private FlushIntervalPolicy() {}

    /** 기기 스펙(lowRam·sdkInt)으로 주기 산정. 값이 없으면 기본값. */
    public static int forDevice(Boolean lowRam, Integer sdkInt) {
        if (Boolean.TRUE.equals(lowRam)) return RELAXED_SEC;
        if (sdkInt != null && sdkInt < LEGACY_SDK_INT) return RELAXED_SEC;
        return DEFAULT_SEC;
    }

    /** 저장된 유저 디바이스 스펙으로 주기 산정. user==null 이면 기본값. */
    public static int forUser(User user) {
        if (user == null) return DEFAULT_SEC;
        return forDevice(user.getLowRam(), user.getSdkInt());
    }
}
