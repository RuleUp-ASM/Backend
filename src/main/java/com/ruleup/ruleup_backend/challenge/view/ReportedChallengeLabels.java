package com.ruleup.ruleup_backend.challenge.view;

import com.ruleup.ruleup_backend.routine.service.RoutineCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * 신고해 가린 방의 <b>제목·설명</b> — 그 방 루틴의 기본 추천값(템플릿 이름·설명)이다.
 *
 * <h4>왜 고정 문구가 아니라 추천값인가</h4>
 * 신고해도 방에서 나가지 않는다 — 나가게 하면 「탈퇴하고 싶은데 감점이 싫어 신고하는」 어뷰징 통로가
 * 된다. 계속 참여하는 방이니 무엇을 인증하는 방인지는 알아야 한다. 템플릿 이름은 사용자가 쓴 글이
 * 아니라 우리가 만든 카탈로그 문구라 원문이 새지 않는다(REP-06 — AI 제목은 원문과 같을 수 있어 안 된다).
 *
 * <p>템플릿 없이 만든 방(수동 인증)은 보여 줄 추천값이 없어 고정 문구를 쓴다.
 */
@Component
@RequiredArgsConstructor
public class ReportedChallengeLabels {

    private final RoutineCatalog catalog;
    private final JdbcTemplate jdbc;

    public record Label(String title, String description) {}

    private static final Label FALLBACK = new Label(ChallengeView.REPORTED_TITLE, null);

    /** 템플릿 id 로 — 엔티티·이력 행을 이미 들고 있을 때. */
    public Label of(Long templateId) {
        if (templateId == null) return FALLBACK;
        return catalog.findById(templateId)
                .map(t -> new Label(t.getName(), t.getDescription()))
                .orElse(FALLBACK);
    }

    /** 챌린지 id 로 — 살아 있는 방이 없으면 보관 이력에서 찾는다. 가린 방은 사람당 몇 건이라 단건 조회다. */
    public Label forChallenge(UUID challengeId) {
        if (challengeId == null) return FALLBACK;
        byte[] id = bytes(challengeId);
        Long templateId = jdbc.query(
                "SELECT COALESCE((SELECT template_id FROM challenges WHERE id = ?), "
                        + "(SELECT template_id FROM challenge_history WHERE challenge_id = ?))",
                rs -> rs.next() ? (Long) rs.getObject(1, Long.class) : null, id, id);
        return of(templateId);
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits()).array();
    }
}
