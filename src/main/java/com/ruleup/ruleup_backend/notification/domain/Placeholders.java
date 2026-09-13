package com.ruleup.ruleup_backend.notification.domain;

import java.util.Map;

/**
 * {@code {param}} 치환 — 제목·본문·딥링크가 <b>같은 엔진</b>을 쓴다.
 *
 * <p>값이 하나라도 비면 <b>예외가 아니라 null</b> 이다. 적재가 도메인 트랜잭션 안에 있어서
 * 렌더링 예외가 곧 강퇴 판정의 롤백이기 때문이다(백엔드 4-1 ③). 부른 쪽이 폴백을 정한다 —
 * 딥링크는 링크 없이 두고, 문구는 일반 폴백 문구로 적재한다.
 */
final class Placeholders {

    private Placeholders() {}

    /** @return 치환이 끝난 문자열. 템플릿이 null 이거나 필요한 값이 없으면 null. */
    static String render(String template, Map<String, String> params) {
        if (template == null) return null;

        String result = template;
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
}
