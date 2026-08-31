package com.ruleup.ruleup_backend.push;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 화면에 뜨는 푸시 — 알림함에 적재된 알림 1건을 사용자에게 알린다.
 *
 * <p>{@link SilentPush} 와 갈라 두는 이유는 정책이다. 무음 푸시는 "사용자가 알림을 거부한 것을
 * 우회해 콘텐츠를 밀어넣는 용도로 쓰지 않는다"는 제약이 붙어 있고, 이쪽은 정확히 그 반대 —
 * 사용자에게 보이는 것이 목적이다. 한 메서드로 합치면 그 구분이 호출부 관례로만 남는다.
 *
 * <p>본문에 <b>민감정보를 담지 않는다</b>. 잠금·정지 사유는 분류명과 진입 링크만 두고 상세는
 * 앱 안에서 본다 — 푸시는 잠금화면에 그대로 뜬다.
 *
 * @param notificationId 알림함 행 ID. 앱이 탭 시 해당 항목을 열 수 있게 데이터로 실어 보낸다.
 * @param deeplink       탭 시 진입 경로. 없으면 알림함으로 간다.
 */
public record DisplayPush(String notificationId, String type, String title, String body, String deeplink) {

    /** FCM data 페이로드 — 값은 문자열만 허용된다. */
    public Map<String, String> data() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("notificationId", notificationId);
        data.put("type", type);
        if (deeplink != null) data.put("deeplink", deeplink);
        return data;
    }
}
