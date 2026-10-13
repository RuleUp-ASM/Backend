package com.ruleup.ruleup_backend.notification.domain;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;

import java.util.List;

/**
 * 알림 문구에 들어가는 루틴(챌린지) 이름 — 결과·남은 루틴 알림이 같은 규칙을 쓴다.
 *
 * <p>잠금화면에 뜨는 문장이라 <b>공개 제목</b>을 쓴다(심사 중이면 임시 제목). 사용자가 쓴 제목이 길면
 * 제목 줄 하나를 혼자 차지하므로 {@value #MAX_LABEL}자에서 자른다.
 */
public final class RoutineLabels {

    static final int MAX_LABEL = 20;
    /** 남은 루틴 목록에 이름으로 보여 줄 최대 개수. 나머지는 「외 N개」로 접는다. */
    static final int MAX_LISTED = 3;
    private static final String FALLBACK = "챌린지";

    private RoutineLabels() {}

    public static String of(Challenge challenge) {
        String title = (challenge == null) ? null : challenge.publicTitle();
        if (title == null || title.isBlank()) return FALLBACK;
        String trimmed = title.strip();
        return trimmed.length() <= MAX_LABEL ? trimmed : trimmed.substring(0, MAX_LABEL - 1) + "…";
    }

    /** 「물 마시기, 러닝, 독서 외 2개」 — 순서는 부른 쪽이 정한다. */
    public static String summary(List<String> labels) {
        if (labels.isEmpty()) return null;
        int shown = Math.min(labels.size(), MAX_LISTED);
        String head = String.join(", ", labels.subList(0, shown));
        int rest = labels.size() - shown;
        return rest == 0 ? head : head + " 외 " + rest + "개";
    }
}
