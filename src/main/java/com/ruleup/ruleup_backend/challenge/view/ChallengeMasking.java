package com.ruleup.ruleup_backend.challenge.view;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 「이 사람이 신고해 차단한 방인가」 — 표시값을 가릴지 정하는 단 하나의 질문.
 *
 * <h4>왜 신고 서비스를 부르지 않는가</h4>
 * 차단 여부는 {@code user_blocks} 한 줄이면 답이 나온다. 그것 하나를 위해 조회 경로 전체가
 * 신고 도메인 서비스에 붙으면, 접수·스냅샷·차단 해제까지 들어 있는 클래스가 탐색·방·인증
 * 조회의 의존성이 된다. 탐색 목록도 같은 이유로 이 표를 직접 읽는다.
 *
 * <h4>목록에서는 반드시 {@link #maskedFor} 를 쓴다</h4>
 * 항목마다 {@link #isMasked} 를 부르면 한 화면에 수십 번의 왕복이 생긴다. 차단 목록은
 * 사람당 몇 건이므로 통째로 받아 두고 메모리에서 맞춰 보는 쪽이 싸다.
 */
@Service
@RequiredArgsConstructor
public class ChallengeMasking {

    private final JdbcTemplate jdbc;

    /** 이 뷰어가 가려서 봐야 할 방 — 목록 경로용. 차단이 없으면 빈 집합이다. */
    public Set<UUID> maskedFor(UUID viewerId) {
        if (viewerId == null) return Set.of();
        List<UUID> ids = jdbc.query(
                "SELECT target_id FROM user_blocks WHERE blocker_id = ? AND target_type = 'CHALLENGE'",
                (rs, row) -> uuid(rs.getBytes(1)), bytes(viewerId));
        return ids.isEmpty() ? Set.of() : new HashSet<>(ids);
    }

    /** 단건 조회용. */
    public boolean isMasked(UUID viewerId, UUID challengeId) {
        if (viewerId == null || challengeId == null) return false;
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_blocks WHERE blocker_id = ? AND target_type = 'CHALLENGE' "
                        + "AND target_id = ?",
                Integer.class, bytes(viewerId), bytes(challengeId));
        return count != null && count > 0;
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits()).array();
    }

    private static UUID uuid(byte[] value) {
        ByteBuffer b = ByteBuffer.wrap(value);
        return new UUID(b.getLong(), b.getLong());
    }
}
