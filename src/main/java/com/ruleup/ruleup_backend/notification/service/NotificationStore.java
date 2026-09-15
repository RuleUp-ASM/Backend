package com.ruleup.ruleup_backend.notification.service;

import com.ruleup.ruleup_backend.notification.domain.Notification;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 적재 실행부 — <b>겹침을 조회로 거르지 않고 INSERT 한 문장 안에서 흡수한다</b>.
 *
 * <h4>선조회로는 못 막는다</h4>
 * {@code exists → save} 는 원자적이지 않다. 조회와 INSERT 사이에 다른 태스크가 같은 멱등키를
 * 커밋하면 이쪽은 조회를 통과한 채 커밋 시점에 UNIQUE 위반으로 터지고, 적재가 도메인
 * 트랜잭션 안에 있으므로 <b>같은 트랜잭션의 인증 판정·강퇴 처리까지 함께 롤백된다</b>.
 *
 * <p>그 경합은 예외적인 사고가 아니라 <b>설계가 전제한 정상 경로</b>다 — 백엔드 4-4 가
 * 「멀티 태스크 중복 실행은 {@code dedup_key} 의 UNIQUE 로 막는다. ShedLock 없이 이 하나로
 * 충분하다」고 적고 있다. 리마인더·공지 팬아웃·휴면 고지·약관 고지 크론이 ECS 태스크 수만큼
 * 동시에 도는 것을 <b>의도한</b> 구조이므로, 그 충돌을 흡수하는 책임은 INSERT 쪽에 있다.
 *
 * <h4>{@code INSERT IGNORE} 가 아니라 {@code ON DUPLICATE KEY UPDATE id = id} 다</h4>
 * {@code IGNORE} 는 중복 키만이 아니라 <b>FK 위반·NOT NULL·값 절단까지 경고로 낮춰</b> 행을
 * 조용히 버린다. 그것은 적재 누락이고 절대 규칙 1 위반이다 — 알림이 사라진 사실을 아무도
 * 모른 채 「고지했다」로 남는다. {@code ON DUPLICATE KEY UPDATE} 는 <b>중복 키만</b> 삼키고
 * 나머지 제약 위반은 그대로 예외로 올린다.
 *
 * <h4>「내가 넣었는가」는 영향 행 수가 아니라 <b>id 로</b> 판정한다</h4>
 * Connector/J 는 기본값이 {@code useAffectedRows=false} 라 CLIENT_FOUND_ROWS 를 켜고 접속한다.
 * 그 상태에서는 값이 바뀌지 않은 UPDATE 도 <b>1을 세기 때문에</b> INSERT 와 구분되지 않는다 —
 * 100행을 넣든 100행이 전부 겹치든 합계가 똑같이 100이다. 영향 행 수로 판정하려면 접속
 * 옵션을 바꿔야 하는데, 그것은 JPA 의 갱신 카운트 해석까지 함께 바꾸는 전역 변경이다.
 *
 * <p>그래서 넣은 뒤에 <b>내 id 가 테이블에 있는지</b> 묻는다. {@code id} 는 발행 시점에 만든
 * UUIDv7 이라 경합에서 이긴 쪽의 행은 id 가 다르다 — 있으면 내가 INSERT 한 것이다.
 * PK 조회 한 번이라 비용은 구 선조회({@code existsByDedupKey})와 같다.
 *
 * <p>내 것이 아닌 행을 큐로 넘기면 안 되기 때문에 이 구분이 필요하다. 경합에서 밀린 행의
 * {@code id} 는 <b>DB 에 없는 값</b>이라, 그대로 발송하면 컨슈머의 {@code pushed_at} 갱신이
 * 0행에 걸리고 클라이언트는 탭할 때 {@code NOTIFICATION_NOT_FOUND} 를 받는다.
 *
 * <p>⚠️ JPA 가 아니라 JDBC 로 즉시 INSERT 하므로 <b>수신자 행이 이미 DB 에 있어야 한다</b>.
 * 같은 트랜잭션에서 막 만든 유저에게 발행하려면 그 유저를 먼저 flush 해야 FK 가 통과한다.
 */
@Component
@RequiredArgsConstructor
class NotificationStore {

    /** 한 문장에 담는 행 수. 500명 팬아웃도 플레이스홀더 상한에 한참 못 미친다. */
    private static final int CHUNK = 100;

    private static final int COLUMNS = 12;

    private static final String INSERT_PREFIX = """
            INSERT INTO notifications
              (id, user_id, tab, type, toggle_group, challenge_id,
               title, body, deeplink, dedup_key, suppress_key, created_at)
            VALUES\s""";

    /** 중복 키만 삼킨다 — 값이 바뀌지 않으므로 영향 행 수는 0 이다. */
    private static final String ON_DUPLICATE = " ON DUPLICATE KEY UPDATE id = id";

    private static final String VALUES_ROW = "(?,?,?,?,?,?,?,?,?,?,?,?)";

    private final JdbcTemplate jdbc;

    /**
     * 적재하고 <b>실제로 들어간 행만</b> 돌려준다. 같은 멱등키가 이미 있으면 그 행은 빠진다 —
     * 발행 재시도이지 오류가 아니다.
     */
    List<Notification> store(List<Notification> rows) {
        if (rows.isEmpty()) return List.of();

        List<Notification> stored = new ArrayList<>(rows.size());
        for (int from = 0; from < rows.size(); from += CHUNK) {
            List<Notification> chunk = rows.subList(from, Math.min(from + CHUNK, rows.size()));
            stored.addAll(insert(chunk));
        }
        return stored;
    }

    private List<Notification> insert(List<Notification> chunk) {
        String sql = INSERT_PREFIX
                + String.join(",", java.util.Collections.nCopies(chunk.size(), VALUES_ROW))
                + ON_DUPLICATE;

        Object[] args = new Object[chunk.size() * COLUMNS];
        int i = 0;
        for (Notification n : chunk) {
            args[i++] = bytes(n.getId());
            args[i++] = bytes(n.getUserId());
            args[i++] = n.getTab();
            args[i++] = n.getType();
            args[i++] = n.getToggleGroup();
            args[i++] = bytes(n.getChallengeId());
            args[i++] = n.getTitle();
            args[i++] = n.getBody();
            args[i++] = n.getDeeplink();
            args[i++] = n.getDedupKey();
            args[i++] = n.getSuppressKey();
            args[i++] = Timestamp.from(n.getCreatedAt());
        }

        jdbc.update(sql, args);
        return landed(chunk);
    }

    /** 이 청크에서 <b>내가 넣은</b> 행. 없는 id 는 같은 멱등키를 남이 먼저 커밋했다는 뜻이다. */
    private List<Notification> landed(List<Notification> chunk) {
        String sql = "SELECT id FROM notifications WHERE id IN ("
                + String.join(",", java.util.Collections.nCopies(chunk.size(), "?")) + ")";
        Object[] ids = chunk.stream().map(n -> (Object) bytes(n.getId())).toArray();

        Set<UUID> present = new HashSet<>(jdbc.query(sql, (rs, row) -> uuid(rs.getBytes(1)), ids));
        return chunk.stream().filter(n -> present.contains(n.getId())).toList();
    }

    private static byte[] bytes(UUID id) {
        if (id == null) return null;
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(id.getMostSignificantBits());
        bb.putLong(id.getLeastSignificantBits());
        return bb.array();
    }

    private static UUID uuid(byte[] raw) {
        ByteBuffer bb = ByteBuffer.wrap(raw);
        return new UUID(bb.getLong(), bb.getLong());
    }
}
