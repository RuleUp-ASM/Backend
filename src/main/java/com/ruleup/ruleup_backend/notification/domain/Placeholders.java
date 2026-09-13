package com.ruleup.ruleup_backend.notification.domain;

import java.util.Map;

/**
 * {@code {param}} 치환 — 제목·본문·딥링크가 <b>같은 엔진</b>을 쓴다.
 *
 * <p>값이 하나라도 비면 <b>예외가 아니라 null</b> 이다. 적재가 도메인 트랜잭션 안에 있어서
 * 렌더링 예외가 곧 강퇴 판정의 롤백이기 때문이다(백엔드 4-1 ③). 부른 쪽이 폴백을 정한다 —
 * 딥링크는 링크 없이 두고, 문구는 일반 폴백 문구로 적재한다.
 *
 * <p><b>치환한 값은 다시 훑지 않는다.</b> 결과 문자열을 처음부터 다시 검색하면 값 안의 중괄호가
 * 새 치환자로 읽힌다 — 방장이 강퇴 사유를 「GPS {오류}로 강퇴」라고 쓰면 {@code {오류}} 를
 * 모르는 파라미터로 보고 null 을 돌려주어, 사유가 통째로 사라지고 「새 알림이 도착했어요」가
 * 적재된다. 사용자가 쓴 문장이 들어오는 값이 {@code reason} · 챌린지 제목 · 루틴 이름 셋이라
 * 실제로 닿는 경로다. 그래서 <b>원본 템플릿만</b> 한 번 훑고 값은 그대로 붙인다.
 */
final class Placeholders {

    private Placeholders() {}

    /** @return 치환이 끝난 문자열. 템플릿이 null 이거나 필요한 값이 없으면 null. */
    static String render(String template, Map<String, String> params) {
        if (template == null) return null;

        int open = template.indexOf('{');
        if (open < 0) return template;   // 치환자가 없는 문구가 대부분이다

        StringBuilder rendered = new StringBuilder(template.length() + 32);
        int cursor = 0;
        while (open >= 0) {
            int close = template.indexOf('}', open);
            if (close < 0) return null;
            String key = template.substring(open + 1, close);
            String value = (params == null) ? null : params.get(key);
            if (value == null || value.isBlank()) return null;

            rendered.append(template, cursor, open).append(value);
            cursor = close + 1;
            // 다음 치환자는 **템플릿에서** 찾는다 — 방금 붙인 값 안은 들여다보지 않는다.
            open = template.indexOf('{', cursor);
        }
        return rendered.append(template, cursor, template.length()).toString();
    }
}
