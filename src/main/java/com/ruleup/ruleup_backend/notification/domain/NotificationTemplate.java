package com.ruleup.ruleup_backend.notification.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.ruleup.ruleup_backend.notification.domain.NotificationParams.*;

/**
 * 문구 레지스트리 — <b>제목·본문이 타입과 {@code params} 만으로 정해진다</b>(백엔드 4-1 ①).
 *
 * <h4>왜 발행부가 아니라 여기인가</h4>
 * 적재가 도메인 트랜잭션 안이라 <b>렌더링 예외가 강퇴 판정을 롤백시킨다</b>. 그래서 스펙은
 * 템플릿을 「enum 과 {@code params} 만 쓰는 순수 함수」로 못 박고 기동 시 전 타입 더미 렌더를
 * 요구한다. 문구가 24개 클래스에 흩어져 있으면 그 검증을 할 자리가 없다 — 무엇이 렌더되는지
 * 아는 곳이 발행 시점뿐이라, 빈 제목이나 컬럼 초과는 <b>그 코드가 실제로 실행될 때</b>에야 드러난다.
 *
 * <h4>변형은 파라미터 하나로 고른다</h4>
 * 한 타입이 여러 사건을 담으면({@code ACCOUNT_SANCTION} 의 집행·해제, {@code VERIFICATION_RESULT}
 * 의 즉시·확정) 그 타입의 템플릿들이 <b>같은 변형 파라미터</b>를 공유하고 값으로 갈린다.
 * 이미 의미 있는 파라미터가 있으면 그것을 쓴다 — 생명주기는 {@code phase}, 티어는
 * {@code direction} 이라 같은 값을 {@code variant} 로 한 번 더 넘기지 않는다.
 *
 * <h4>문구가 없는 두 타입</h4>
 * {@code ANNOUNCEMENT} · {@code MARKETING} 은 <b>사람이 쓴 문장</b>이 곧 내용이다. 공지와 캠페인은
 * 운영자가 콘솔에서 제목·본문을 직접 적고, 그것을 템플릿으로 환원할 방법이 없다.
 * {@link #AUTHORED_TYPES} 가 그 예외이고, 기동 검증이 <b>그 둘만</b> 템플릿 없이 남아 있는지 본다.
 *
 * <h4>문구에 민감정보를 담지 않는다</h4>
 * 잠금 사유·CS 답변 전문·인증 사진은 여기 들어오지 않는다(백엔드 10절). 잠금화면에 뜨는 문장이다.
 */
public enum NotificationTemplate {

    // ===== 계정 그룹 =====

    /** 강퇴 사유는 방장이 직접 쓴 문장이라 본문이 통째로 값이다. */
    CHALLENGE_KICKED(NotificationType.CHALLENGE_KICKED,
            "챌린지에서 내보내졌어요", "{reason}"),

    /**
     * 제재 집행 — 수단 3종 × 기한 유무. 기한이 있으면 「해제 예정일」을 문장에 넣고, 날짜 자체는
     * 넣지 않는다. 상세는 제재 이력이 소유한다.
     */
    ACCOUNT_SANCTION_BAN(NotificationType.ACCOUNT_SANCTION, VARIANT, "BAN",
            "계정이 영구 정지됐어요", "자세한 내용은 마이페이지에서 확인해주세요."),
    ACCOUNT_SANCTION_BAN_UNTIL(NotificationType.ACCOUNT_SANCTION, VARIANT, "BAN_UNTIL",
            "계정이 영구 정지됐어요",
            "해제 예정일까지 일부 기능을 이용할 수 없어요. 자세한 내용은 마이페이지에서 확인해주세요."),
    ACCOUNT_SANCTION_LOCK(NotificationType.ACCOUNT_SANCTION, VARIANT, "LOCK",
            "계정이 잠겼어요", "자세한 내용은 마이페이지에서 확인해주세요."),
    ACCOUNT_SANCTION_LOCK_UNTIL(NotificationType.ACCOUNT_SANCTION, VARIANT, "LOCK_UNTIL",
            "계정이 잠겼어요",
            "해제 예정일까지 일부 기능을 이용할 수 없어요. 자세한 내용은 마이페이지에서 확인해주세요."),
    ACCOUNT_SANCTION_FEATURE(NotificationType.ACCOUNT_SANCTION, VARIANT, "FEATURE_SUSPENSION",
            "일부 기능이 정지됐어요", "자세한 내용은 마이페이지에서 확인해주세요."),
    ACCOUNT_SANCTION_FEATURE_UNTIL(NotificationType.ACCOUNT_SANCTION, VARIANT,
            "FEATURE_SUSPENSION_UNTIL", "일부 기능이 정지됐어요",
            "해제 예정일까지 일부 기능을 이용할 수 없어요. 자세한 내용은 마이페이지에서 확인해주세요."),
    /** 재검토로 제재가 풀린 경우. 집행 고지와 같은 타입이라 알림함에서 한 줄기로 읽힌다. */
    ACCOUNT_SANCTION_REVOKED(NotificationType.ACCOUNT_SANCTION, VARIANT, "REVOKED",
            "제재가 해제됐어요", "재검토 결과 제재가 해제됐어요. 다시 이용하실 수 있어요."),

    DORMANCY_NOTICE(NotificationType.DORMANCY_NOTICE,
            "곧 휴면 계정이 돼요", "한동안 활동이 없어 곧 휴면으로 전환돼요. 지금 들어오시면 그대로 유지돼요."),

    INACTIVE_WITHDRAWAL_NOTICE(NotificationType.INACTIVE_WITHDRAWAL_NOTICE,
            "오랫동안 들어오지 않으셨어요", "계정이 곧 정리될 예정이에요. 계속 쓰시려면 한 번만 들어와주세요."),

    /**
     * 심사 거부 — 대상별로 고칠 화면과 문장이 다르다.
     *
     * <p>{@code NICKNAME_TAKEN} 만 성격이 다르다. 심사 결과가 아니라 <b>복원 중 선점 충돌</b>이라
     * 「기준 위반」이라고 쓰면 안 된다 — 잘못한 것이 없는 사람에게 위반 통보가 간다.
     */
    MODERATION_REJECTED_NICKNAME(NotificationType.MODERATION_REJECTED, VARIANT, "NICKNAME",
            "닉네임을 바꿔주세요",
            "회원님의 닉네임이 커뮤니티 기준에 맞지 않아 다른 사용자에게는 임시 닉네임으로 표시됩니다. "
                    + "닉네임을 변경하면 다시 노출됩니다."),
    MODERATION_REJECTED_NICKNAME_TAKEN(NotificationType.MODERATION_REJECTED, VARIANT,
            "NICKNAME_TAKEN", "닉네임을 변경해주세요",
            "쓰시던 닉네임을 다른 분이 사용 중이라 임시 닉네임으로 시작했어요. 프로필에서 새 닉네임을 정해주세요."),
    MODERATION_REJECTED_PROFILE_IMAGE(NotificationType.MODERATION_REJECTED, VARIANT,
            "PROFILE_IMAGE", "프로필 사진을 바꿔주세요",
            "회원님의 프로필 사진이 커뮤니티 기준에 맞지 않아 다른 사용자에게는 숨겨집니다. "
                    + "사진을 변경하면 다시 노출됩니다."),
    MODERATION_REJECTED_CHALLENGE_TEXT(NotificationType.MODERATION_REJECTED, VARIANT,
            "CHALLENGE_TEXT", "챌린지 제목·설명을 바꿔주세요",
            "[{challenge_title}] 챌린지의 제목 또는 설명이 커뮤니티 기준에 맞지 않아요. "
                    + "수정 전까지 다른 사람에게는 임시 제목으로 보여요."),
    /** 운영자가 「콘텐츠만 문제」로 되돌린 경우 — 제목·설명·이미지를 한꺼번에 본다. */
    MODERATION_REJECTED_CHALLENGE_CONTENT(NotificationType.MODERATION_REJECTED, VARIANT,
            "CHALLENGE_CONTENT", "챌린지 내용을 바꿔주세요",
            "[{challenge_title}] 챌린지의 내용이 커뮤니티 기준에 맞지 않아요. "
                    + "수정 전까지 다른 사람에게는 임시 제목으로 보여요."),

    CHALLENGE_IMAGE_REMOVED(NotificationType.CHALLENGE_IMAGE_REMOVED,
            "챌린지 대표 이미지를 바꿔주세요",
            "[{challenge_title}] 챌린지의 대표 이미지가 커뮤니티 기준에 맞지 않아 내렸어요. 새 이미지를 올려주세요."),

    PERMISSION_REGRANT_REQUIRED(NotificationType.PERMISSION_REGRANT_REQUIRED,
            "인증 권한을 다시 허용해주세요",
            "권한이 없어 자동 인증이 기록되지 않고 있어요. 방 설정에서 다시 허용해주세요."),

    /** 부정행위 강퇴 — 방 이름을 쓰지 않는다. 영구 차단이라 그 방은 이미 「내 챌린지」에 없다. */
    CHEAT_DETECTED(NotificationType.CHEAT_DETECTED,
            "챌린지에서 내보내졌어요",
            "이 챌린지에는 다시 참여할 수 없어요. 자세한 내용은 제재 이력에서 확인해주세요."),

    /**
     * 이의 인용. <b>기각 문구는 아직 없다</b> — 기각을 고지하는 발행부가 없기 때문이다.
     * 기각 경로가 생기면 {@code REJECTED} 변형을 여기 추가한다. 변형으로 갈라 둔 이유가 그것이다:
     * 변형 없이 단일 문구로 두면 기각 발행이 <b>인용 문구를 그대로 써버린다</b>.
     */
    APPEAL_RESULT_ACCEPTED(NotificationType.APPEAL_RESULT, VARIANT, "ACCEPTED",
            "이의가 받아들여졌어요", "인증이 완료로 정정됐어요. 진행률과 연속 기록도 함께 되돌렸어요."),

    TERMS_UPDATED(NotificationType.TERMS_UPDATED,
            "약관이 개정됐어요", "계속 이용하시려면 새 약관에 동의해주세요."),

    DEVICE_LOGGED_OUT(NotificationType.DEVICE_LOGGED_OUT,
            "다른 기기에서 로그인됨",
            "새 기기에서 로그인되어 기존 기기의 세션이 종료됐어요. 본인이 아니라면 계정 보안을 확인해주세요."),

    /** CS 답변 — <b>본문을 싣지 않는다</b>. 잠금화면에 답변 전문이 뜨면 곤란한 사연이 있다. */
    CS_ANSWERED(NotificationType.CS_ANSWERED,
            "문의에 답변이 등록됐어요", "보내주신 문의에 답변이 등록됐어요. 눌러서 확인해주세요."),

    // ===== 챌린지 그룹 =====

    /**
     * 판정 결과 — <b>즉시 성공</b>과 <b>확정</b>은 같은 사건이 아니다. 앞은 그날 안에 채운 것이고,
     * 뒤는 귀속일 이틀 뒤 00:00 에 되돌릴 수 없게 굳은 것이다.
     */
    VERIFICATION_RESULT_MANUAL(NotificationType.VERIFICATION_RESULT, VARIANT, "MANUAL_SUCCESS",
            "인증이 완료됐어요", "오늘 몫을 체크했어요. 진행률에 반영됐어요."),
    VERIFICATION_RESULT_SYNC(NotificationType.VERIFICATION_RESULT, VARIANT, "SYNC_SUCCESS",
            "인증이 완료됐어요", "오늘 몫을 채웠어요. 진행률에 반영됐어요."),
    VERIFICATION_RESULT_CONFIRMED(NotificationType.VERIFICATION_RESULT, VARIANT, "CONFIRMED_SUCCESS",
            "인증이 완료로 확정됐어요", "그날 몫을 채웠어요. 진행률에 반영됐어요."),
    VERIFICATION_RESULT_FAILED(NotificationType.VERIFICATION_RESULT, VARIANT, "CONFIRMED_FAILURE",
            "인증이 실패로 확정됐어요", "이의 기간이 지나 이 결과는 되돌릴 수 없어요."),

    CONSECUTIVE_FAILURE_WARNING(NotificationType.CONSECUTIVE_FAILURE_WARNING,
            "연속으로 인증을 놓치고 있어요",
            "한 번 더 놓치면 이 챌린지에서 나가게 돼요. 다음 사이클은 꼭 채워보세요."),

    /** 생명주기는 {@code phase} 로 갈린다 — 멱등 키가 이미 그 값을 쓰고 있어 변형 키를 따로 두지 않는다. */
    CHALLENGE_LIFECYCLE_STARTED(NotificationType.CHALLENGE_LIFECYCLE, PHASE, "STARTED",
            "챌린지가 시작됐어요", "오늘부터 인증이 시작돼요. 첫 인증을 잊지 마세요."),
    CHALLENGE_LIFECYCLE_ENDED(NotificationType.CHALLENGE_LIFECYCLE, PHASE, "ENDED",
            "챌린지가 끝났어요", "수고하셨어요. 최종 결과를 확인해보세요."),

    WATCHER_INVITATION_EXPIRED(NotificationType.WATCHER_INVITATION_EXPIRED,
            "감시자 초대가 만료됐어요",
            "보내신 감시자 초대 링크가 7일이 지나 만료됐어요. 필요하면 다시 초대해주세요."),

    /** 티어는 {@code direction} 으로 갈린다 — 멱등·억제 키가 이미 그 값을 쓴다. */
    TIER_CHANGED_UP(NotificationType.TIER_CHANGED, DIRECTION, "UP",
            "티어가 올랐어요", "축하해요! 새 티어로 올라섰어요."),
    TIER_CHANGED_DOWN(NotificationType.TIER_CHANGED, DIRECTION, "DOWN",
            "티어가 내려갔어요", "점수가 내려가 티어가 조정됐어요."),
    TIER_BOUNDARY_NEAR_UP(NotificationType.TIER_BOUNDARY_NEAR, DIRECTION, "UP",
            "다음 티어가 코앞이에요", "조금만 더 쌓으면 다음 티어예요."),
    TIER_BOUNDARY_NEAR_DOWN(NotificationType.TIER_BOUNDARY_NEAR, DIRECTION, "DOWN",
            "티어가 내려갈 수 있어요", "점수가 조금만 더 내려가면 티어가 조정돼요."),

    /**
     * 감시자에게 가는 실패 통지 — 담는 것은 <b>누가·어느 방의·어느 약속을</b> 지키지 못했는지
     * 셋뿐이다. 인증 사진·사유·상세는 넣지 않는다(백엔드 10절).
     */
    PENALTY_FAILURE_SHARED(NotificationType.PENALTY_FAILURE_SHARED,
            "감시 알림", "{actor_name}님이 [{challenge_title}]의 {routine_name} 약속을 지키지 못했어요."),

    /** 반응은 실패 당사자에게 간다. 보낸 사람을 밝히지 않으면 응원도 놀림도 의미가 없다. */
    WATCHER_REACTION_CHEER(NotificationType.WATCHER_REACTION, VARIANT, "CHEER",
            "응원이 도착했어요", "{actor_name}님이 반응을 보냈어요."),
    WATCHER_REACTION_TEASE(NotificationType.WATCHER_REACTION, VARIANT, "TEASE",
            "놀림이 도착했어요", "{actor_name}님이 반응을 보냈어요."),

    // ===== 그룹 없음 =====

    ROUTINE_REMINDER(NotificationType.ROUTINE_REMINDER,
            "오늘 인증할 루틴이 남아 있어요",
            "아직 인증하지 않은 루틴이 있어요. 오늘이 지나기 전에 확인해주세요.");

    /**
     * 문구를 <b>사람이 쓰는</b> 두 타입. 운영자가 콘솔에서 제목·본문을 직접 적으므로 템플릿이 없다.
     * 기동 검증이 이 집합과 「템플릿 없는 타입」이 정확히 일치하는지 본다 — 템플릿을 빠뜨린
     * 타입이 여기 조용히 섞여 들어가면 그 알림은 폴백 문구로 나간다.
     */
    public static final Set<NotificationType> AUTHORED_TYPES =
            EnumSet.of(NotificationType.ANNOUNCEMENT, NotificationType.MARKETING);

    private static final Map<NotificationType, List<NotificationTemplate>> BY_TYPE =
            new EnumMap<>(NotificationType.class);

    static {
        for (NotificationTemplate t : values()) {
            BY_TYPE.computeIfAbsent(t.type, k -> new ArrayList<>()).add(t);
        }
    }

    private final NotificationType type;
    /** 변형을 고르는 파라미터 이름. 변형이 없는 타입은 null. */
    private final String variantParam;
    private final String variantValue;
    private final String titleTemplate;
    private final String bodyTemplate;

    NotificationTemplate(NotificationType type, String title, String body) {
        this(type, null, null, title, body);
    }

    NotificationTemplate(NotificationType type, String variantParam, String variantValue,
                         String title, String body) {
        this.type = type;
        this.variantParam = variantParam;
        this.variantValue = variantValue;
        this.titleTemplate = title;
        this.bodyTemplate = body;
    }

    /**
     * 렌더링 — <b>순수 함수</b>다. 타입과 {@code params} 외에는 아무것도 읽지 않는다.
     *
     * <p>맞는 템플릿이 없거나 문구에 필요한 값이 비면 <b>null 을 준다</b>. 예외를 던지면
     * 알림 하나가 도메인 판정을 되돌린다 — 적재 단계가 일반 폴백 문구로 메운다.
     */
    public static Rendered render(NotificationType type, Map<String, String> params) {
        NotificationTemplate template = find(type, params);
        if (template == null) return Rendered.EMPTY;
        return new Rendered(Placeholders.render(template.titleTemplate, params),
                Placeholders.render(template.bodyTemplate, params));
    }

    /** 이 타입의 템플릿 중 {@code params} 에 맞는 것. 없으면 null. */
    static NotificationTemplate find(NotificationType type, Map<String, String> params) {
        List<NotificationTemplate> candidates = BY_TYPE.get(type);
        if (candidates == null) return null;

        for (NotificationTemplate candidate : candidates) {
            if (candidate.variantParam == null) return candidate;   // 변형이 없는 타입
            String value = (params == null) ? null : params.get(candidate.variantParam);
            if (candidate.variantValue.equals(value)) return candidate;
        }
        return null;
    }

    /** 기동 검증용 — 선언된 템플릿 전부. */
    public static List<NotificationTemplate> of(NotificationType type) {
        return BY_TYPE.getOrDefault(type, List.of());
    }

    /** 이 템플릿이 렌더되려면 {@code params} 에 있어야 하는 변형 값. 변형이 없으면 빈 맵이다. */
    public Map<String, String> variantParams() {
        return (variantParam == null) ? Map.of() : Map.of(variantParam, variantValue);
    }

    public NotificationType type() {
        return type;
    }

    public String titleTemplate() {
        return titleTemplate;
    }

    public String bodyTemplate() {
        return bodyTemplate;
    }

    /** 타입에 선언된 변형 파라미터. 한 타입의 템플릿들은 전부 같은 값을 써야 한다. */
    public String variantParam() {
        return variantParam;
    }

    /** 템플릿을 가진 타입 전부 — 기동 검증이 {@link #AUTHORED_TYPES} 와의 합집합을 확인한다. */
    public static Set<NotificationType> templatedTypes() {
        return Arrays.stream(NotificationType.values())
                .filter(t -> BY_TYPE.containsKey(t))
                .collect(java.util.stream.Collectors.toCollection(
                        () -> EnumSet.noneOf(NotificationType.class)));
    }

    /** 렌더링 결과. 값이 null 이면 적재 단계가 폴백 문구로 메운다. */
    public record Rendered(String title, String body) {
        static final Rendered EMPTY = new Rendered(null, null);
    }
}
