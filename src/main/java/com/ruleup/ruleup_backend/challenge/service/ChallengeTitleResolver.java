package com.ruleup.ruleup_backend.challenge.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 챌린지 id → 사용자에게 보여줄 챌린지명.
 *
 * <p>id 만 들고 있는 화면(점수 변동 이력·알림·감사 로그)이 「OO 챌린지 · 사이클 성공 +8」처럼
 * 사람이 읽는 문장을 그리려면 이름이 필요한데, <b>항목마다 방 상세를 조회하게 만들 수는 없다.</b>
 *
 * <p>원천이 둘이다. 살아 있는 방은 {@code challenges}, 완료 후 하드 삭제된 방은 삭제 배치가
 * 직전에 적재한 {@code challenge_history.title_snapshot} 이다. <b>둘 다 없으면 null</b>이며
 * 이건 정상 상태다 — 삭제 배치가 도입되기 전에 사라진 방은 이름을 복원할 방법이 없다.
 * 호출부는 null 을 「이름을 모른다」로 그려야 하고, 빈 문자열이나 "알 수 없음"으로 채우지 않는다.
 *
 * <p>살아 있는 방은 {@link Challenge#publicTitle()} 을 쓴다 — 심사 중·거부 상태의 원문이
 * 다른 화면으로 새지 않게 하는 대체 규칙이 여기서도 그대로 적용돼야 한다.
 */
@Component
@RequiredArgsConstructor
public class ChallengeTitleResolver {

    private final ChallengeRepository challengeRepository;
    private final JdbcTemplate jdbc;

    /** null id 는 조용히 버린다 — 계정 단위 변동처럼 챌린지가 없는 항목이 섞여 들어온다. */
    @Transactional(readOnly = true)
    public Map<UUID, String> titlesOf(Collection<UUID> challengeIds) {
        Set<UUID> ids = new LinkedHashSet<>(challengeIds.stream().filter(Objects::nonNull).toList());
        if (ids.isEmpty()) return Map.of();

        Map<UUID, String> titles = new HashMap<>();
        for (Challenge c : challengeRepository.findAllById(ids)) {
            titles.put(c.getId(), c.publicTitle());
        }

        List<UUID> missing = ids.stream().filter(id -> !titles.containsKey(id)).toList();
        if (!missing.isEmpty()) titles.putAll(fromHistory(missing));
        return titles;
    }

    private Map<UUID, String> fromHistory(List<UUID> ids) {
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        Object[] args = ids.stream().map(ChallengeTitleResolver::toBytes).toArray();
        Map<UUID, String> found = new HashMap<>();
        jdbc.query("SELECT challenge_id, title_snapshot FROM challenge_history WHERE challenge_id IN ("
                + placeholders + ")", rs -> {
            found.put(toUuid(rs.getBytes(1)), rs.getString(2));
        }, args);
        return found;
    }

    private static byte[] toBytes(UUID u) {
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(u.getMostSignificantBits());
        bb.putLong(u.getLeastSignificantBits());
        return bb.array();
    }

    private static UUID toUuid(byte[] bytes) {
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        return new UUID(bb.getLong(), bb.getLong());
    }
}
