package com.ruleup.ruleup_backend.notification.domain;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 레지스트리 기동 검증 — <b>어긋나면 뜨지 않는다</b>. 백엔드 4-1.
 *
 * <p>적재가 도메인 트랜잭션 안으로 들어온 대가로 세 가지를 지키기로 했다. ① 템플릿은 enum 과
 * {@code params} 만 쓰는 순수 함수 ② <b>기동 시 전 타입 더미 렌더 검증</b> ③ 파라미터 누락 시
 * 예외 대신 폴백. 이 클래스가 ②이고, <b>제목·본문·딥링크 셋을 다 본다</b>.
 *
 * <p>런타임 폴백(파라미터가 없으면 null)이 있기 때문에 오히려 이게 필요하다 —
 * {@code {challege_id}} 오타 하나가 예외 없이 <b>모든 딥링크를 null 로 만들고</b>, 문구 쪽이라면
 * 모든 고지를 「새 알림이 도착했어요」로 만든다. 그 사실은 사용자가 알림을 눌러 아무 데도 가지
 * 못할 때에야, 혹은 CS 로 「무슨 일인지 모르겠다」가 들어올 때에야 드러난다.
 */
@Slf4j
@Component
public class NotificationRegistryValidator implements InitializingBean {

    /** 딥링크는 전부 커스텀 스킴이다 — https 앱링크를 쓰지 않아 외부 웹 노출 이슈를 피한다. */
    private static final String SCHEME = "ruleup://";

    /** 컬럼 길이. 넘으면 적재가 잘려 멱등 키가 서로 겹친다. */
    private static final int KEY_MAX = 160;

    /** {@code notifications.title} · {@code body} 의 컬럼 길이. 넘으면 적재 단계가 잘라 낸다. */
    private static final int TITLE_MAX = 100;
    private static final int BODY_MAX = 500;

    @Override
    public void afterPropertiesSet() {
        log.info("알림 레지스트리 검증 완료 — {}종", validate());
    }

    /** @return 검증한 타입 수 */
    public int validate() {
        Map<String, String> dummy = dummyParams();
        for (NotificationType type : NotificationType.values()) {
            // 치환자 검사는 **원본 템플릿**에 해야 한다 — 렌더된 결과에는 자리가 남아 있지 않다.
            checkTemplate(type.name(), type.deeplinkTemplate());
            checkRender(type, dummy);
            checkKeyLength(type, dummy);
            checkTexts(type, dummy);
        }
        return NotificationType.values().length;
    }

    /**
     * 딥링크 템플릿 하나를 검사한다. 치환자가 <b>선언된 파라미터 이름</b>인지, 닫혀 있는지,
     * 커스텀 스킴인지를 본다.
     */
    public static void checkTemplate(String typeName, String template) {
        if (template == null) return;   // 딥링크 없는 타입 — 기기 로그아웃·마케팅·공지

        checkPlaceholders(typeName, template);
        if (!template.startsWith(SCHEME)) throw new IllegalStateException(
                "알림 딥링크는 " + SCHEME + " 여야 한다: type=" + typeName + " template=" + template);
    }

    /** 치환자가 닫혀 있고 <b>선언된 파라미터 이름</b>인지. 제목·본문·딥링크가 같이 쓴다. */
    private static void checkPlaceholders(String label, String template) {
        if (template == null) return;

        int cursor = 0;
        while (true) {
            int open = template.indexOf('{', cursor);
            if (open < 0) break;
            int close = template.indexOf('}', open);
            if (close < 0) throw new IllegalStateException(
                    "알림 템플릿의 치환자가 닫히지 않았다: at=" + label + " template=" + template);
            String name = template.substring(open + 1, close);
            if (!declaredParams().contains(name)) throw new IllegalStateException(
                    "알림 템플릿이 모르는 파라미터를 참조한다: at=" + label + " param=" + name);
            cursor = close + 1;
        }
    }

    /**
     * 제목·본문 — <b>선언된 변형을 전부 더미 렌더</b>해 본다.
     *
     * <p>변형이 하나라도 비거나 컬럼을 넘기면 그 사건은 「새 알림이 도착했어요」로 나가거나
     * 잘려 나간다. 발행부에 문구가 흩어져 있던 동안에는 이걸 볼 자리가 없었다 — 그 코드가
     * 실제로 실행되는 순간에야 드러났다.
     */
    private static void checkTexts(NotificationType type, Map<String, String> dummy) {
        List<NotificationTemplate> templates = NotificationTemplate.of(type);

        if (NotificationTemplate.AUTHORED_TYPES.contains(type)) {
            // 운영자가 쓴 문장이 곧 내용인 타입. 템플릿이 생겼다면 둘 중 하나가 틀린 것이다.
            if (!templates.isEmpty()) throw new IllegalStateException(
                    "문구를 발행부가 주는 타입에 템플릿이 선언돼 있다: type=" + type);
            return;
        }
        if (templates.isEmpty()) throw new IllegalStateException(
                "문구 템플릿이 없는 타입이다 — 이대로 발행하면 폴백 문구로 나간다: type=" + type);

        String variantParam = templates.getFirst().variantParam();
        for (NotificationTemplate template : templates) {
            // 한 타입의 변형들이 서로 다른 키로 갈리면 어느 쪽도 안정적으로 선택되지 않는다.
            if (!java.util.Objects.equals(variantParam, template.variantParam()))
                throw new IllegalStateException(
                        "한 타입의 변형이 서로 다른 파라미터로 갈린다: type=" + type
                                + " param=" + variantParam + " vs " + template.variantParam());

            checkPlaceholders(template.name() + ".title", template.titleTemplate());
            checkPlaceholders(template.name() + ".body", template.bodyTemplate());

            Map<String, String> params = new HashMap<>(dummy);
            params.putAll(template.variantParams());
            NotificationTemplate.Rendered rendered = NotificationTemplate.render(type, params);

            require(rendered.title(), template, "제목", TITLE_MAX);
            require(rendered.body(), template, "본문", BODY_MAX);
        }
    }

    private static void require(String rendered, NotificationTemplate template,
                                String field, int max) {
        if (rendered == null || rendered.isBlank()) throw new IllegalStateException(
                "파라미터를 다 채웠는데도 " + field + " 이(가) 렌더되지 않는다: template=" + template);
        if (rendered.length() > max) throw new IllegalStateException(
                field + " 이(가) 컬럼을 넘는다: template=" + template
                        + " len=" + rendered.length() + " max=" + max);
    }

    /**
     * 실제 값을 넣어 만든 키가 컬럼에 들어가는지 본다. UUID 를 채워 최악에 가깝게 잡는다 —
     * 잘리면 서로 다른 사건이 같은 키를 갖게 되고 UNIQUE 가 뒤 사건의 <b>적재를</b> 삼킨다.
     */
    private static void checkKeyLength(NotificationType type, Map<String, String> dummy) {
        UUID user = UUID.randomUUID();
        String dedup = type.dedupKey(user, dummy);
        if (dedup != null && dedup.length() > KEY_MAX) throw new IllegalStateException(
                "dedup_key 가 컬럼을 넘는다: type=" + type + " len=" + dedup.length());

        String suppress = type.suppressKey(dummy);
        if (suppress != null && suppress.length() > KEY_MAX) throw new IllegalStateException(
                "suppress_key 가 컬럼을 넘는다: type=" + type + " len=" + suppress.length());
    }

    /**
     * 더미 렌더 — 파라미터를 전부 채운 상태에서 <b>실제로 문자열이 나오는지</b> 확인한다.
     * 템플릿이 있는데 렌더 결과가 null 이면 치환 로직이 그 모양을 다루지 못한다는 뜻이다.
     */
    private static void checkRender(NotificationType type, Map<String, String> dummy) {
        if (type.deeplinkTemplate() == null) return;

        String rendered = type.deeplink(dummy);
        if (rendered == null) throw new IllegalStateException(
                "파라미터를 다 채웠는데도 딥링크가 렌더되지 않는다: type=" + type);
        if (rendered.indexOf('{') >= 0 || rendered.indexOf('}') >= 0) throw new IllegalStateException(
                "알림 딥링크에 치환되지 않은 자리가 남았다: type=" + type + " rendered=" + rendered);
    }

    /** {@link NotificationParams} 에 선언된 키 전부. 리플렉션이라 상수를 추가하면 자동으로 따라온다. */
    private static Set<String> declaredParams() {
        Set<String> names = new HashSet<>();
        for (Field f : NotificationParams.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || f.getType() != String.class) continue;
            try {
                names.add((String) f.get(null));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("알림 파라미터 상수를 읽을 수 없다: " + f.getName(), e);
            }
        }
        return names;
    }

    private static Map<String, String> dummyParams() {
        Map<String, String> dummy = new HashMap<>();
        declaredParams().forEach(name -> dummy.put(name, UUID.randomUUID().toString()));
        return dummy;
    }
}
