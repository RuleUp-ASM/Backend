package com.ruleup.ruleup_backend.notification.domain;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/**
 * 알림 타입 레지스트리 <b>21종</b> — 알림 및 알림함 기능 스펙 6-1 #3·#4·#14.
 *
 * <p>타입마다 분류·딥링크·중복 제어 주기·토글 가능 여부를 선언한다. <b>분기를 if 문으로 흩뿌리지
 * 않고 레지스트리 조회 한 번으로 끝내는 것</b>이 확장성의 핵심이다 — 페이지2에서 공지·댓글
 * 알림 5종을 합류시킬 때 여기 항목만 추가하면 된다.
 *
 * <p>DB 컬럼은 ENUM 이 아니라 VARCHAR 다. 타입 추가에 DDL 이 필요 없게 하기 위함이라,
 * <b>모르는 값이 들어와도 조회가 깨지지 않아야 한다</b>({@link #find} 가 Optional 을 준다).
 *
 * <h4>딥링크</h4>
 * {@code {target}} 은 발행 시점의 {@code targetKey} 로 치환된다. 감시자 통지가 방이 아니라
 * 수신 관리 화면으로 가는 것은 의도된 것이다 — 감시자에게 방 상세·랭킹·멤버 진입점을 주지 않는다.
 */
public enum NotificationType {

    // ===== A · 필수 11종 — 시각 무관 즉시 발송, 토글 불가, 중복 제어 없음 =====

    /** 강퇴 확정. 사유와 근거는 제재 이력에서 본다 — 이미 나간 방으로 보낼 수 없다. */
    CHALLENGE_KICKED(NotificationCategory.A, "ruleup://me/sanctions"),
    /** 계정 잠금·해제. */
    ACCOUNT_SANCTION(NotificationCategory.A, "ruleup://me/sanctions"),
    /** 휴면 사전 고지 — 30일 7일 전·1일 전. */
    DORMANCY_NOTICE(NotificationCategory.A, "ruleup://home"),
    /** 1년 미활동 탈퇴 고지 — 30일 전. */
    INACTIVITY_WITHDRAWAL(NotificationCategory.A, "ruleup://home"),
    /** 모더레이션 거부(닉네임·프로필 사진) — 수정 UI 로 바로 진입해야 한다. */
    MODERATION_REJECTED(NotificationCategory.A, "ruleup://profile/edit"),
    /** 챌린지 이미지 삭제 — 방 수정 화면으로 진입한다. */
    CHALLENGE_IMAGE_REMOVED(NotificationCategory.A, "ruleup://challenges/{target}/edit"),
    /** 권한 재허용 요청 — 인증 설정 화면으로 바로 보낸다. 2사이클 내 미해소면 강퇴다. */
    PERMISSION_REGRANT_REQUIRED(NotificationCategory.A, "ruleup://challenges/{target}/setup"),
    /** 부정행위 검출 통지 — 검출 1회가 곧 강퇴·영구 차단이다. */
    CHEAT_DETECTED(NotificationCategory.A, "ruleup://me/sanctions"),
    /** 이의 처리 결과. */
    APPEAL_RESULT(NotificationCategory.A, "ruleup://me/calendar"),
    /** 약관 변경 고지 — 재동의 화면으로 보낸다. */
    TERMS_UPDATED(NotificationCategory.A, "ruleup://settings/agreements"),
    /** 다른 기기 로그인으로 세션이 종료됨. 로그아웃 상태라 진입점을 두지 않는다. */
    DEVICE_LOGGED_OUT(NotificationCategory.A, null),

    // ===== B · 기능 9종 — 토글·음소거·중복 제어·야간 보류 대상 =====

    /** 루틴 리마인더 — 08:00 · 12:00 · 19:00, 당일 판정 예정 루틴 보유자에게만. */
    ROUTINE_REMINDER(NotificationCategory.B, "ruleup://challenges/{target}", true),
    /** 날짜별 판정 결과. */
    VERIFICATION_RESULT(NotificationCategory.B, "ruleup://me/calendar", true),
    /** 티어 승급·강등 확정. */
    TIER_CHANGED(NotificationCategory.B, "ruleup://me/tier"),
    /** 티어 경계 5점 이내 도달 — <b>중복 금지가 1주</b>인 유일한 예외다. */
    TIER_BOUNDARY_NEAR(NotificationCategory.B, "ruleup://me/tier", false, Duration.ofDays(7)),
    /** 연속 실패 경고 — 2사이클 시점. 3사이클이면 강퇴다. */
    CONSECUTIVE_FAILURE_WARNING(NotificationCategory.B, "ruleup://challenges/{target}", true),
    /** 챌린지 시작·종료. */
    CHALLENGE_LIFECYCLE(NotificationCategory.B, "ruleup://challenges/{target}", true),
    /**
     * 패널티 실패 공유 — 감시자에게 가는 통지.
     * 방 상세가 아니라 수신 관리 화면으로 보낸다. 감시자는 방 멤버가 아니다.
     */
    PENALTY_FAILURE_SHARED(NotificationCategory.B, "ruleup://watching/notices/{target}", true),
    /** 감시자 초대 만료 — <b>생성자에게만</b> 간다. 감시자 후보는 아직 동의하지 않은 외부인이다. */
    WATCHER_INVITATION_EXPIRED(NotificationCategory.B, "ruleup://challenges/{target}/watchers"),
    /** 응원·놀림 반응 — 실패 당사자 1명에게만. 감시자 닉네임을 공개한다. */
    WATCHER_REACTION(NotificationCategory.B, "ruleup://me/calendar"),

    // ===== C · 마케팅 1종 =====

    /** 마케팅·이벤트. 페이지1은 수신 측 규칙만 구현하며 캠페인 발송 도구는 백오피스 소관이다. */
    MARKETING(NotificationCategory.C, null);

    private final NotificationCategory category;
    private final String deeplinkTemplate;
    /** 챌린지별 음소거 대상인지 — 챌린지 컨텍스트가 있는 기능 알림만 해당된다. */
    private final boolean muteable;
    private final Duration dedupWindow;

    NotificationType(NotificationCategory category, String deeplinkTemplate) {
        this(category, deeplinkTemplate, false, null);
    }

    NotificationType(NotificationCategory category, String deeplinkTemplate, boolean muteable) {
        this(category, deeplinkTemplate, muteable, null);
    }

    NotificationType(NotificationCategory category, String deeplinkTemplate, boolean muteable,
                     Duration dedupWindow) {
        this.category = category;
        this.deeplinkTemplate = deeplinkTemplate;
        this.muteable = muteable;
        // A 는 중복 제어를 적용하지 않는다 — 고지 의무가 있는 알림을 서버가 삼키면 안 된다.
        // 기본 24시간. 티어 경계만 1주로 덮어쓴다. enum 생성자는 자기 클래스의 static 필드를
        // 참조할 수 없어 상수로 빼지 않고 여기 둔다.
        this.dedupWindow = (category == NotificationCategory.A)
                ? null : (dedupWindow != null ? dedupWindow : Duration.ofHours(24));
    }

    public NotificationCategory category() {
        return category;
    }

    /** 설정 화면에 토글을 노출하고 끌 수 있는지. 필수(A)는 <b>컴포넌트 자체를 렌더링하지 않는다</b>. */
    public boolean isTogglable() {
        return category != NotificationCategory.A;
    }

    public boolean isMuteable() {
        return muteable;
    }

    /** null 이면 중복 제어를 적용하지 않는다(필수 알림). */
    public Duration dedupWindow() {
        return dedupWindow;
    }

    /** {@code {target}} 을 치환한 진입 경로. 대상이 필요한데 없으면 링크를 주지 않는다. */
    public String deeplink(String targetKey) {
        if (deeplinkTemplate == null) return null;
        if (!deeplinkTemplate.contains("{target}")) return deeplinkTemplate;
        return (targetKey == null || targetKey.isBlank())
                ? null : deeplinkTemplate.replace("{target}", targetKey);
    }

    /**
     * DB 에 저장된 문자열을 타입으로. <b>모르는 값이면 empty</b> 다 —
     * 타입 추가가 DDL 없이 가능해야 하므로, 롤백 후 남은 행 때문에 알림함이 깨지면 안 된다.
     */
    public static Optional<NotificationType> find(String raw) {
        return Arrays.stream(values()).filter(t -> t.name().equals(raw)).findFirst();
    }
}
