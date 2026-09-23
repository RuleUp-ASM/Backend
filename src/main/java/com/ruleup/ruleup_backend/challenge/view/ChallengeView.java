package com.ruleup.ruleup_backend.challenge.view;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;

/**
 * 사용자에게 내려보내는 챌린지의 <b>표시값</b> — 제목·설명·이미지.
 *
 * <h4>왜 한 곳에 모으는가</h4>
 * 같은 방이 화면마다 다르게 보이면 안 된다. 그런데 이 세 값은 원본을 그대로 내리는 자리가
 * 아니라 <b>보는 사람에 따라 갈리는</b> 값이다 — 심사 중이면 남에게는 가려지고, 신고해 차단한
 * 사람에게는 또 다르게 보인다. 규칙을 각 조회 서비스가 따로 적으면 한 곳만 고쳐지고,
 * 「탐색에서는 가려졌는데 방에 들어가면 보이는」 상태가 생긴다. 실제로 그랬다 —
 * 신고 응답은 {@code CHALLENGE_MASKED} 를 돌려주는데 상세·방 화면은 원문 그대로였다.
 *
 * <h4>가리는 방식이 심사와 같은 이유</h4>
 * 자리를 새로 만들지 않는다. 심사에 걸린 방을 가리는 자리(AI 임시 제목 · 빈 설명 · 기본 이미지)가
 * 이미 있고, 클라이언트는 그 세 값을 그릴 줄 안다. 신고 차단에만 쓰는 네 번째 표현을 만들면
 * 안드로이드가 분기를 하나 더 구현해야 하고, 그 분기는 <b>신고자에게만</b> 보이므로 검증도 어렵다.
 *
 * <h4>제목만은 AI 임시 제목이 아니라 고정 문구다</h4>
 * AI 제목을 그대로 받아 만든 방은 {@code ai_title == title} 이다(심사 면제의 조건이 바로 그것이다).
 * 그런 방에서 신고자에게 AI 제목을 내리면 가린 것이 아니라 <b>원문을 그대로</b> 보여 준다(REP-06).
 * 심사 가려짐은 "사용자가 바꾼 원문"을 가리는 것이라 AI 제목으로 충분하지만, 신고 가려짐은 방 자체를
 * 안 보고 싶다는 뜻이므로 어떤 이름도 내리지 않는다. 자리는 그대로(문자열 하나)라 클라 분기는 늘지 않는다.
 */
public record ChallengeView(String title, String description, String imageUrl) {

    /** 신고해 가린 방의 표시 제목. 차단 목록 화면과 같은 문구다. */
    public static final String REPORTED_TITLE = "숨김 처리된 챌린지";

    /**
     * 남의 화면. 심사에 걸렸거나 신고해 차단한 방이면 대체값으로 내린다.
     *
     * @param masked 이 뷰어가 신고해 차단한 방인가
     */
    public static ChallengeView of(Challenge c, boolean masked) {
        if (masked) return hidden(c);
        return new ChallengeView(c.publicTitle(), c.publicDescription(), c.publicImageUrl());
    }

    /**
     * 방장 본인 화면. 심사 중이어도 <b>자기가 넣은 값</b>을 본다 — 남에게 어떻게 보이는지는
     * 심사 상태로 알린다. 다만 자기 방을 신고해 차단했다면 그 선택이 우선한다.
     */
    public static ChallengeView forOwner(Challenge c, boolean masked) {
        if (masked) return hidden(c);
        return new ChallengeView(c.getTitle(), c.getDescription(), c.getImageUrl());
    }

    /** 보는 사람이 방장인지에 따라 갈라 준다. */
    public static ChallengeView of(Challenge c, boolean viewerIsOwner, boolean masked) {
        return viewerIsOwner ? forOwner(c, masked) : of(c, masked);
    }

    private static ChallengeView hidden(Challenge c) {
        return new ChallengeView(REPORTED_TITLE, null, null);
    }
}
