package com.ruleup.ruleup_backend.notification.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * 알림함 탭 — 공통 #8(2026-09-07 확정).
 *
 * <p>운영자 공지를 별도 테이블·별도 API 로 두지 않고 {@code notifications} 에 흡수하고
 * <b>탭 필터로 가른다</b>. 구 {@code GET /api/v1/announcements} 는 폐기됐다.
 *
 * <p>읽음 커서도 탭별로 따로 보관한다. 카디널리티가 2뿐이지만 인덱스
 * {@code (user_id, tab, id)} 의 2번 컬럼으로 넣는다 — 빼면 공지 탭이 50건을 채우려고
 * 6개월치 900행을 끝까지 스캔한다.
 */
public enum NotificationTab {

    /** 기본 탭. 파라미터를 주지 않으면 여기다 — 공지가 섞이지 않는다. */
    NOTIFICATION((byte) 0),

    /** 운영자 공지. 푸시가 나가지 않고 알림 센터에만 남는다. */
    ANNOUNCEMENT((byte) 1);

    private final byte code;

    NotificationTab(byte code) {
        this.code = code;
    }

    /** DB 저장값 — TINYINT. */
    public byte code() {
        return code;
    }

    public static Optional<NotificationTab> find(String raw) {
        if (raw == null) return Optional.empty();
        return Arrays.stream(values()).filter(t -> t.name().equals(raw)).findFirst();
    }

    public static NotificationTab of(byte code) {
        return code == 1 ? ANNOUNCEMENT : NOTIFICATION;
    }
}
