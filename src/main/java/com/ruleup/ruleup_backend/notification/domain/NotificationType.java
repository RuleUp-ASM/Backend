package com.ruleup.ruleup_backend.notification.domain;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.ruleup.ruleup_backend.notification.domain.NotificationParams.*;

/**
 * 알림 타입 레지스트리 <b>22종</b> — 백엔드 테크 스펙 5절, 공통 8절.
 *
 * <h4>테이블이 아니라 코드 enum이다</h4>
 * {@code notification_types} 레지스트리 테이블은 2026-09-08 제거됐다. 타입 코드 · 토글 그룹 ·
 * 딥링크 · {@code pushable} · 억제 인터벌 · 탭이 전부 여기 불변 값으로 있다.
 * <b>대가를 알고 간다</b> — 인터벌의 무배포 변경이 불가능해지고 타입별 킬 스위치가 없어진다.
 * 다만 컨슈머 정지가 더 강한 대체 수단이다: 적재는 계속되고 푸시만 멈추므로 절대 규칙 1을
 * 지키면서 전면 차단이 된다.
 *
 * <h4>두 종류의 키는 층이 다르다</h4>
 * <ul>
 *   <li>{@link #dedupKey} — <b>1회성 멱등.</b> UNIQUE 가 INSERT 단계에서 막는다. 전 타입이 가진다.
 *   <li>{@link #suppressKey} — <b>반복성 억제.</b> 같은 키가 여러 행에 반복해 붙고, 발송 단계에서
 *       {@code pushed_at} 조회로 판정한다. <b>6종만</b> 쓴다.
 * </ul>
 * 하나로 합칠 수 없다. UNIQUE 로 억제까지 하면 두 번째 알림이 <b>알림 센터에도 안 들어가서</b>
 * 절대 규칙 1(모든 알림은 예외 없이 적재된다)을 어긴다.
 *
 * <h4>타입 코드는 구현 이름 기준이다</h4>
 * {@code notifications.type} 이 적재된 값이라 개명은 데이터 마이그레이션이다(공통 8절).
 */
public enum NotificationType {

    // ===== 계정 그룹 11종 =====

    /** 강퇴 확정. 이미 나간 방으로 보낼 수 없어 제재 이력으로 보낸다. */
    CHALLENGE_KICKED(NotificationToggleGroup.ACCOUNT, "ruleup://me/sanctions",
            new String[]{EVENT_KEY}),

    /** 계정 잠금·정지. 사유와 해제일은 제재 이력이 소유한다. */
    ACCOUNT_SANCTION(NotificationToggleGroup.ACCOUNT, "ruleup://me/sanctions",
            new String[]{EVENT_KEY}),

    /** 휴면 전환 예고. */
    DORMANCY_NOTICE(NotificationToggleGroup.ACCOUNT, "ruleup://home",
            new String[]{EVENT_KEY}),

    /** 장기 미접속 탈퇴 예고. */
    INACTIVE_WITHDRAWAL_NOTICE(NotificationToggleGroup.ACCOUNT, "ruleup://home",
            new String[]{EVENT_KEY}),

    /**
     * 심사 거부 — 닉네임·프로필 사진은 같은 편집 화면이라 한 타입으로 충분하다.
     * 챌린지 제목·설명 거부만 진입점이 달라 발행부가 딥링크를 재정의한다.
     *
     * <p>키에 {@code event_key} 를 함께 넣는다. {@code target_key}(=nickname) 하나로 두면
     * UNIQUE 가 <b>평생</b> 두 번째 거부를 막아 재제출 후 거부가 알림함에 들어가지 않는다.
     */
    MODERATION_REJECTED(NotificationToggleGroup.ACCOUNT, "ruleup://profile/edit",
            new String[]{TARGET_KEY, EVENT_KEY}),

    /** 챌린지 대표 이미지 삭제. 같은 방에서 재발할 수 있어 {@code event_key} 를 함께 넣는다. */
    CHALLENGE_IMAGE_REMOVED(NotificationToggleGroup.ACCOUNT,
            "ruleup://challenges/{challenge_id}/edit",
            new String[]{CHALLENGE_ID, EVENT_KEY}),

    /**
     * 인증 권한 재허용 요청 — <b>방별</b> 인증 권한이라 딥링크가 방 설정이다(공통 8절 정정).
     *
     * <p>억제 키에 {@code challenge_id} 를 넣은 것이 핵심이다(9/8 변경). 유저 단위 24h 로 두면
     * <b>2개 방에서 권한이 막힌 유저가 한쪽 알림만 받고 다른 방은 2사이클 내 미해소로 강퇴</b>된다.
     */
    PERMISSION_REGRANT_REQUIRED(NotificationToggleGroup.ACCOUNT,
            "ruleup://challenges/{challenge_id}/setup",
            new String[]{EVENT_KEY},
            new String[]{PERMISSION, CHALLENGE_ID}, Duration.ofHours(24)),

    /**
     * 부정행위 검출 — 검출 1회가 곧 강퇴·영구 차단이다.
     *
     * <p>확정값은 {@code ruleup://me/cheat-history} 지만 받침할 화면·API 가 없어 아직
     * {@code me/sanctions} 를 보낸다(공통 #18). 없는 화면을 가리키는 것이 잘못된 화면을
     * 가리키는 것보다 나쁘다 — 구 {@code verification/{id}} 가 빈 화면으로 갔던 그 문제다.
     */
    CHEAT_DETECTED(NotificationToggleGroup.ACCOUNT, "ruleup://me/sanctions",
            new String[]{EVENT_KEY}),

    /**
     * 이의 처리 결과. 이의 <b>상세</b>로 보내는 편이 낫지만 단건 조회 API 가 없어 현황 목록으로 간다.
     */
    APPEAL_RESULT(NotificationToggleGroup.ACCOUNT, "ruleup://me/appeals",
            new String[]{APPEAL_ID}),

    /** 약관 개정. 개별 약관 상세로 보내면 나머지 재동의 항목을 놓치므로 동의 목록으로 보낸다. */
    TERMS_UPDATED(NotificationToggleGroup.ACCOUNT, "ruleup://settings/agreements",
            new String[]{EVENT_KEY}),

    /**
     * 기기 로그아웃 — <b>딥링크 없음</b>. 이 알림을 받는 순간 그 기기는 로그아웃 상태라
     * 어디로 보내도 로그인 벽에 막힌다. 탭하면 클라이언트가 알림함으로 폴백한다.
     */
    DEVICE_LOGGED_OUT(NotificationToggleGroup.ACCOUNT, null,
            new String[]{EVENT_KEY}),

    // ===== 챌린지 그룹 8종 =====

    /** 인증 판정 결과 — 방 상세의 「오늘」 카드. 앱에 인증 상세 단독 화면이 없다. */
    VERIFICATION_RESULT(NotificationToggleGroup.CHALLENGE, "ruleup://challenges/{challenge_id}",
            new String[]{VERIFICATION_ID}),

    /**
     * 연속 실패 경고 — 강퇴 직전 고지다.
     *
     * <p>인터벌은 스펙에서 미정으로 남아 있던 유일한 값이며 <b>24시간으로 확정</b>했다(2026-09-08).
     * 다른 억제 타입의 기본값과 같고, 같은 루틴의 경고가 하루에 두 번 이상 울릴 이유가 없다.
     */
    CONSECUTIVE_FAILURE_WARNING(NotificationToggleGroup.CHALLENGE,
            "ruleup://challenges/{challenge_id}",
            new String[]{EVENT_KEY},
            new String[]{CHALLENGE_ID, ROUTINE_ID}, Duration.ofHours(24)),

    /** 챌린지 시작·종료. 진입점이 같아 한 타입이고 {@code phase} 로 가른다. */
    CHALLENGE_LIFECYCLE(NotificationToggleGroup.CHALLENGE, "ruleup://challenges/{challenge_id}",
            new String[]{CHALLENGE_ID, PHASE}),

    /** 감시자 초대 만료 — 초대를 보낸 생성자에게 간다. */
    WATCHER_INVITATION_EXPIRED(NotificationToggleGroup.CHALLENGE,
            "ruleup://challenges/{challenge_id}/watchers",
            new String[]{CHALLENGE_ID, WATCHER_ID}),

    /**
     * 매너 온도 티어 변동. <b>키에 {@code challenge_id} 를 넣으면 안 된다</b> — 유저 단위 점수라
     * 넣으면 3개 방 참여자가 같은 승급 알림을 3번 받는다.
     *
     * <p>{@code direction} 하나로 두면 UNIQUE 가 평생 두 번째 승급을 막으므로
     * {@code event_key}(티어 변동 이력 id)를 함께 넣는다.
     */
    TIER_CHANGED(NotificationToggleGroup.CHALLENGE, "ruleup://me/tier",
            new String[]{DIRECTION, EVENT_KEY}),

    /** 티어 경계 근접 — <b>1주</b> 억제. 억제 키에도 {@code challenge_id} 를 넣지 않는다. */
    TIER_BOUNDARY_NEAR(NotificationToggleGroup.CHALLENGE, "ruleup://me/tier",
            new String[]{EVENT_KEY},
            new String[]{DIRECTION}, Duration.ofDays(7)),

    /**
     * 감시자에게 가는 실패 통지 — <b>수신자가 방 멤버가 아니다</b>. 그래서 방 상세가 아니라
     * 수신 관리 화면으로 보내고, {@code notifications.challenge_id} 도 NULL 로 둔다
     * (감시자의 「내 챌린지」 목록에 그 방이 없어 카운터가 뜰 자리가 없다).
     */
    PENALTY_FAILURE_SHARED(NotificationToggleGroup.CHALLENGE, "ruleup://watching/notices/{notice_id}",
            new String[]{EVENT_KEY},
            new String[]{CHALLENGE_ID, ROUTINE_ID, TARGET_USER_ID}, Duration.ofHours(24)),

    /** 감시자가 남긴 응원 반응 — 수신자는 감시자가 아니라 <b>실패 당사자</b>다. */
    WATCHER_REACTION(NotificationToggleGroup.CHALLENGE, "ruleup://me/calendar",
            new String[]{EVENT_KEY},
            new String[]{CHALLENGE_ID, SENDER_ID}, Duration.ofHours(24)),

    // ===== 그룹 없음 2종 =====

    /**
     * 루틴 리마인더 — 08:00 · 12:00 · 19:00 KST. <b>그룹 토글이 없다</b>(상시).
     *
     * <p>키에 슬롯이 있어 억제가 불필요하다. 멀티 태스크 중복 실행은 이 UNIQUE 하나로 막으며
     * ShedLock 이 필요 없는 이유다.
     */
    ROUTINE_REMINDER(NotificationToggleGroup.NONE, "ruleup://challenges/{challenge_id}",
            new String[]{DATE, SLOT}),

    /**
     * 운영자 공지 — <b>{@code pushable = false}</b>. 적재는 되지만 큐에 들어가지 않아
     * 알림 센터에만 남는다.
     *
     * <p>이 속성을 운영 토글로 두지 않는 이유는 하나다: 누가 켜면 공지가 2만 명에게 푸시로 나간다.
     */
    ANNOUNCEMENT(NotificationToggleGroup.NONE, NotificationTab.ANNOUNCEMENT, false, null,
            new String[]{ANNOUNCEMENT_ID}, null, null),

    // ===== 마케팅 1종 =====

    /**
     * 광고성 프로모션 — 수신 동의자에게 08~21시에만. 딥링크는 캠페인이 발행 시 재정의한다.
     *
     * <p>인터벌은 캠페인별이라고 스펙이 적었으나 캠페인 레지스트리가 아직 없다. 억제 키가
     * {@code campaign_id} 라 <b>새 캠페인은 이전 캠페인에 막히지 않으므로</b>, 여기 24시간은
     * 한 캠페인 안의 중복 발송만 막는다.
     */
    MARKETING(NotificationToggleGroup.MARKETING, null,
            new String[]{EVENT_KEY},
            new String[]{CAMPAIGN_ID}, Duration.ofHours(24));

    private final NotificationToggleGroup toggleGroup;
    private final NotificationTab tab;
    private final boolean pushable;
    private final String deeplinkTemplate;
    private final String[] dedupParams;
    private final String[] suppressParams;
    private final Duration suppressInterval;

    NotificationType(NotificationToggleGroup toggleGroup, String deeplinkTemplate,
                     String[] dedupParams) {
        this(toggleGroup, NotificationTab.NOTIFICATION, true, deeplinkTemplate,
                dedupParams, null, null);
    }

    NotificationType(NotificationToggleGroup toggleGroup, String deeplinkTemplate,
                     String[] dedupParams, String[] suppressParams, Duration suppressInterval) {
        this(toggleGroup, NotificationTab.NOTIFICATION, true, deeplinkTemplate,
                dedupParams, suppressParams, suppressInterval);
    }

    NotificationType(NotificationToggleGroup toggleGroup, NotificationTab tab, boolean pushable,
                     String deeplinkTemplate, String[] dedupParams,
                     String[] suppressParams, Duration suppressInterval) {
        this.toggleGroup = toggleGroup;
        this.tab = tab;
        this.pushable = pushable;
        this.deeplinkTemplate = deeplinkTemplate;
        this.dedupParams = dedupParams;
        this.suppressParams = suppressParams;
        this.suppressInterval = suppressInterval;
    }

    // ===== 레지스트리 속성 =====

    public NotificationToggleGroup toggleGroup() {
        return toggleGroup;
    }

    public NotificationTab tab() {
        return tab;
    }

    /** 큐에 넣을지. <b>공지만 false</b> 다. */
    public boolean isPushable() {
        return pushable;
    }

    /** null 이면 인터벌 억제를 적용하지 않는다 — 22종 중 6종만 값이 있다. */
    public Duration suppressInterval() {
        return suppressInterval;
    }

    // ===== 키 =====

    /**
     * 발행 멱등 키 — {@code {TYPE}:{userId}:{식별자}}. 전 타입이 선언하고 있다.
     *
     * <p><b>파라미터가 하나라도 비면 null 을 준다 — 예외를 던지지 않는다.</b> 적재가 도메인
     * 트랜잭션 안에 있으므로 여기서 던지면 알림 파라미터 누락이 강퇴 판정을 롤백시킨다(4-1).
     * 키가 없으면 멱등 보호만 잃고 적재는 그대로 되므로 절대 규칙 1 은 지켜진다.
     * 발행부의 실수는 발행 시점 경고 로그로 드러난다.
     *
     * <p>빈 값을 조용히 이어 붙이지 않는 것도 같은 이유다 — 서로 다른 사건이 같은 키를 갖게 되면
     * UNIQUE 가 뒤 사건의 <b>적재 자체를</b> 삼켜 규칙 1 위반이 조용히 일어난다.
     */
    public String dedupKey(UUID userId, Map<String, String> params) {
        String id = join(dedupParams, params);
        return id == null ? null : name() + ":" + userId + ":" + id;
    }

    /** 인터벌 억제 키 — {@code {TYPE}:{식별자}}. 억제를 쓰지 않는 타입은 <b>null</b> 이다. */
    public String suppressKey(Map<String, String> params) {
        if (suppressParams == null) return null;
        String id = join(suppressParams, params);
        return id == null ? null : name() + ":" + id;
    }

    /** 선언한 파라미터를 순서대로 잇는다. 하나라도 비면 null. */
    private String join(String[] keys, Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (String key : keys) {
            String value = (params == null) ? null : params.get(key);
            if (value == null || value.isBlank()) return null;
            if (!sb.isEmpty()) sb.append(':');
            sb.append(value);
        }
        return sb.toString();
    }

    // ===== 딥링크 =====

    /**
     * {@code {param}} 을 치환한 진입 경로. 필요한 값이 없으면 <b>링크를 주지 않는다</b> —
     * 깨진 경로로 보내느니 클라이언트가 알림함으로 폴백하는 편이 낫다.
     */
    public String deeplink(Map<String, String> params) {
        if (deeplinkTemplate == null) return null;
        String result = deeplinkTemplate;
        int open;
        while ((open = result.indexOf('{')) >= 0) {
            int close = result.indexOf('}', open);
            if (close < 0) return null;
            String key = result.substring(open + 1, close);
            String value = (params == null) ? null : params.get(key);
            if (value == null || value.isBlank()) return null;
            result = result.substring(0, open) + value + result.substring(close + 1);
        }
        return result;
    }

    /**
     * DB 에 저장된 문자열을 타입으로. <b>모르는 값이면 empty</b> 다 — 타입 추가가 DDL 없이
     * 가능해야 하므로, 롤백 후 남은 행 때문에 알림함이 깨지면 안 된다.
     */
    public static Optional<NotificationType> find(String raw) {
        if (raw == null) return Optional.empty();
        return Arrays.stream(values()).filter(t -> t.name().equals(raw)).findFirst();
    }

}
