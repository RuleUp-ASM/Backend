package com.ruleup.ruleup_backend.push;

import java.util.Map;

/**
 * "고스트(무음) 푸시" 페이로드 — 화면에 알림을 띄우지 않고 앱만 깨우는 데이터 전용 메시지.
 *
 * <p>FCM 기준으로는 {@code notification} 블록 없이 {@code data}만 담은 메시지(iOS는 content-available).
 * UI 문구(title/body)가 없으므로 사용자에게 보이지 않고, 앱이 백그라운드에서 받아 처리(예: 권한/셋업 재확인)한다.
 *
 * @param type 앱이 분기할 이벤트 종류(예: "SETUP_REQUIRED")
 * @param data 부가 데이터(문자열 KV — FCM data 규격). 값은 문자열만 허용된다.
 */
public record SilentPush(String type, Map<String, String> data) {

    public static final String TYPE_SETUP_REQUIRED = "SETUP_REQUIRED";
    public static final String TYPE_PERMISSION_REQUIRED = "PERMISSION_REQUIRED";
    /** sync 가 한동안 오지 않은 사용자의 앱을 깨워 쌓아 둔 신호를 올리게 한다(Doze·앱 미기동 복구). */
    public static final String TYPE_SYNC_REQUIRED = "SYNC_REQUIRED";

    /** 셋업/권한 미완료 멤버에게 앱을 깨워 재설정을 유도하는 무음 푸시. */
    public static SilentPush setupRequired(String challengeId) {
        return new SilentPush(TYPE_SETUP_REQUIRED, Map.of("challengeId", challengeId));
    }

    /**
     * 셋업 완료(READY) 후 특정 신호의 권한이 회수된 멤버에게 앱을 깨워 권한 재요청을 유도하는 무음 푸시.
     * signalType 으로 클라가 어떤 권한을 다시 요청할지 분기한다(없으면 생략).
     */
    public static SilentPush permissionRequired(String challengeId, String signalType) {
        Map<String, String> data = new java.util.HashMap<>();
        data.put("challengeId", challengeId);
        if (signalType != null) data.put("signalType", signalType);
        return new SilentPush(TYPE_PERMISSION_REQUIRED, data);
    }

    /**
     * sync 가 끊긴 사용자의 앱을 깨운다 — 앱은 받으면 expedited WorkManager 로 버퍼에 쌓인 신호를 올린다.
     * 대상 방이 여럿이어도 sync 는 사용자 단위라 한 번만 보낸다. 부가 데이터는 없다.
     */
    public static SilentPush syncRequired() {
        return new SilentPush(TYPE_SYNC_REQUIRED, Map.of());
    }
}
