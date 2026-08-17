package com.ruleup.ruleup_backend.room.service;

import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.room.dto.CrossRankingDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/** 매일 생성된 고정 스냅샷만 읽어 페이지 사이 순위가 흔들리지 않게 한다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CrossRankingService {
    private final JdbcTemplate jdbc;

    private record Cursor(int rank, UUID challengeId) {}

    public CrossRankingDtos.Response get(String rawMode, UUID challengeId, String rawCursor, Integer requestedSize) {
        String mode = validateMode(rawMode);
        int size = requestedSize == null ? 20 : Math.max(1, Math.min(requestedSize, 50));
        Cursor cursor = decode(rawCursor);
        String seek = cursor == null ? "" : "AND (rank_no>? OR (rank_no=? AND challenge_id>?)) ";
        Object[] args = cursor == null
                ? new Object[]{mode, size + 1}
                : new Object[]{mode, cursor.rank(), cursor.rank(), bytes(cursor.challengeId()), size + 1};
        List<CrossRankingDtos.Item> fetched = jdbc.query(
                "SELECT rank_no,challenge_id,title,member_count,total_count,success_rate " +
                        "FROM challenge_cross_ranking_snapshot WHERE mode=? AND rank_no IS NOT NULL " + seek +
                        "ORDER BY rank_no,challenge_id LIMIT ?",
                (rs, row) -> new CrossRankingDtos.Item(rs.getInt(1), uuid(rs.getBytes(2)).toString(),
                        rs.getString(3), rs.getInt(4), rs.getInt(5), rs.getBigDecimal(6)), args);
        boolean hasNext = fetched.size() > size;
        List<CrossRankingDtos.Item> items = hasNext ? fetched.subList(0, size) : fetched;
        String next = hasNext && !items.isEmpty()
                ? encode(new Cursor(items.get(items.size() - 1).rank(),
                        UUID.fromString(items.get(items.size() - 1).challengeId()))) : null;
        CrossRankingDtos.MyChallenge mine = challengeId == null ? null : jdbc.query(
                "SELECT challenge_id,rank_no,success_rate,total_count FROM challenge_cross_ranking_snapshot " +
                        "WHERE mode=? AND challenge_id=?",
                rs -> {
                    if (!rs.next()) return null;
                    Integer rank = (Integer) rs.getObject(2);
                    return new CrossRankingDtos.MyChallenge(uuid(rs.getBytes(1)).toString(), rank, rank != null,
                            rs.getInt(4) == 0 ? null : rs.getBigDecimal(3), rs.getInt(4));
                }, mode, bytes(challengeId));
        Timestamp updated = jdbc.query("SELECT MAX(snapshot_at) FROM challenge_cross_ranking_snapshot WHERE mode=?",
                rs -> rs.next() ? rs.getTimestamp(1) : null, mode);
        return new CrossRankingDtos.Response(mine, items,
                updated == null ? null : updated.toInstant().toString(), next);
    }

    private String validateMode(String raw) {
        if (!"GROUP".equals(raw) && !"SOLO".equals(raw))
            throw new BusinessException(ErrorCode.INVALID_RANKING_MODE);
        return raw;
    }

    private Cursor decode(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8);
            int split = decoded.indexOf('|');
            if (split <= 0) throw new IllegalArgumentException();
            return new Cursor(Integer.parseInt(decoded.substring(0, split)),
                    UUID.fromString(decoded.substring(split + 1)));
        } catch (RuntimeException e) { throw new BusinessException(ErrorCode.CURSOR_INVALID); }
    }

    private String encode(Cursor cursor) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (cursor.rank() + "|" + cursor.challengeId()).getBytes(StandardCharsets.UTF_8));
    }

    static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
    static UUID uuid(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
