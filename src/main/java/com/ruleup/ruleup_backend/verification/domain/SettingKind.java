package com.ruleup.ruleup_backend.verification.domain;

/** 날짜별로 되짚어야 하는 멤버 인증 설정의 종류. */
public enum SettingKind {
    /** 인증 장소(GeoAnchor[]). 변경 즉시 적용된다. */
    ANCHORS,
    /** 스크린타임 대상 앱(ScreenApp[]). 변경은 다음 날 00:00 부터 적용된다. */
    SCREEN_APPS
}
