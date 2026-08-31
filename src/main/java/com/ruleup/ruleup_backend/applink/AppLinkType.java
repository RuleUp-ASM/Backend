package com.ruleup.ruleup_backend.applink;

/**
 * 우리가 발급하는 앱링크(딥링크)의 종류와 경로 규칙.
 *
 * <p>발급하는 쪽과 검사하는 쪽이 같은 규칙을 써야 한다 — 각자 문자열을 조립하면 어느 한쪽만 고쳐졌을 때
 * "발급은 되는데 검사에서 떨어지는" 링크가 생긴다. 그래서 경로 조각을 여기 한 곳에 둔다.
 */
public enum AppLinkType {

    /** 챌린지 멤버 초대 — 비공개 방 입장 수단. */
    CHALLENGE_INVITATION("c"),

    /** 감시자 초대 — 외부인에게 보내는 동의 요청. */
    WATCHER_INVITATION("w");

    private final String segment;

    AppLinkType(String segment) { this.segment = segment; }

    /** URL 의 첫 경로 조각. */
    public String segment() { return segment; }

    /** 경로 조각으로 타입을 찾는다. 모르는 조각이면 null — 지원하지 않는 링크다. */
    public static AppLinkType ofSegment(String segment) {
        for (AppLinkType type : values()) if (type.segment.equals(segment)) return type;
        return null;
    }
}
