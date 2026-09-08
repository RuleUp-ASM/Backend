package com.ruleup.ruleup_backend.notification.domain;

/**
 * 발행 파라미터 키 — 렌더링과 키 구성의 <b>유일한 입력</b>이다.
 *
 * <p>백엔드 스펙 4-1 이 요구하는 것은 「템플릿은 enum + params 만 쓰는 순수 함수」다. 적재가
 * 도메인 트랜잭션 안으로 들어왔으므로 <b>렌더링 예외가 강퇴 판정을 롤백시킬 수 있고</b>,
 * 그걸 막으려면 템플릿이 엔티티를 들여다보지 않아야 한다. 그래서 발행부는 필요한 값을
 * 전부 문자열로 풀어 이 키들로 넘긴다.
 *
 * <p>키 이름을 상수로 두는 이유는 오타 때문이다. {@code "challege_id"} 한 글자가
 * 딥링크를 통째로 null 로 만들고, 그건 배포 후에야 드러난다.
 */
public final class NotificationParams {

    private NotificationParams() {}

    /**
     * 발행 사건의 식별자 — <b>같은 사건을 두 번 발행하지 못하게 하는 멱등 토큰</b>이다.
     *
     * <p>스펙 표가 「dedup: 멱등키만」이라고 적은 타입은 이 값 하나로 키가 정해진다.
     * 제재 id · 판정 id 처럼 발행측이 이미 들고 있는 불변 식별자를 넘긴다.
     */
    public static final String EVENT_KEY = "event_key";

    public static final String CHALLENGE_ID = "challenge_id";
    public static final String ROUTINE_ID = "routine_id";
    /** 실패 당사자 — 감시자에게 가는 통지의 억제 단위다. */
    public static final String TARGET_USER_ID = "target_user_id";
    /** 반응을 남긴 감시자. */
    public static final String SENDER_ID = "sender_id";
    /** 감시자 수신 관리의 통지 ID — 챌린지 id 와 다르다. */
    public static final String NOTICE_ID = "notice_id";
    public static final String WATCHER_ID = "watcher_id";
    /** 막힌 권한의 종류(CAMERA · LOCATION …). */
    public static final String PERMISSION = "permission";
    /** 티어 변동 방향(UP · DOWN). */
    public static final String DIRECTION = "direction";
    /** 챌린지 생명주기 단계(STARTED · ENDED). */
    public static final String PHASE = "phase";
    public static final String APPEAL_ID = "appeal_id";
    public static final String VERIFICATION_ID = "verification_id";
    /** 심사 거부 대상(nickname · profile_image · challenge_title …). */
    public static final String TARGET_KEY = "target_key";
    public static final String ANNOUNCEMENT_ID = "announcement_id";
    public static final String CAMPAIGN_ID = "campaign_id";
    /** 리마인더 대상 일자(KST, ISO-8601). */
    public static final String DATE = "date";
    /** 리마인더 슬롯(MORNING · NOON · EVENING). */
    public static final String SLOT = "slot";
}
