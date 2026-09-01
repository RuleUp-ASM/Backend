package com.ruleup.ruleup_backend.challenge.service;

import com.ruleup.ruleup_backend.challenge.explore.store.ExploreIndexer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * 챌린지 하드 삭제 — 방 데이터(잔디·공지·감시자·멤버)를 FK 안전 순서로 물리 삭제한다.
 * 감시자 동의 이력도 함께 지운다 — 관계가 사라지면 그 동의가 유효했다는 사실을 다툴 대상 자체가 없다.
 * 자동 삭제 배치(만료·유령방)가 이력 스냅샷 적재 후 호출한다(방장 수동 삭제는 폐기).
 * 인증 기록 원본(VerificationDaily)·이의·통계(RoutineOutcome)는 보존한다(소프트 참조 — V6).
 * 대표 이미지는 URL로만 참조하므로 DB 행 삭제와 무관하게 스토리지에 남는다 — 호출부가 imageUrl을 로깅한다.
 */
@Component
@RequiredArgsConstructor
public class ChallengeHardDeleter {

    @PersistenceContext
    private EntityManager entityManager;

    private final ExploreIndexer exploreIndexer;

    /** 챌린지 challengeId 와 그 자식 행 전부를 물리 삭제한다. 호출부의 @Transactional 안에서 실행. */
    public void hardDelete(UUID challengeId) {
        // 대기 중인 영속 변경(알림 insert 등)을 먼저 DB에 반영(네이티브 삭제가 최신 상태를 지우도록).
        entityManager.flush();
        // 파생 인덱스에서 뺄 때 필요하다 — 행이 사라진 뒤에는 읽을 수 없으므로 지금 읽어 둔다.
        String category = readCategory(challengeId);
        // 인증 기록 원본(VerificationDaily·MethodResult)·이의(Objection)·통계(RoutineOutcome)는 보존한다
        // (자동 삭제 스펙: 잔디는 삭제, 인증 원본·통계값 보존 — V6 소프트 참조 전환).
        // 감시자 — 반응 → 통지 → 이력 → 관계 순으로 잎에서부터 지운다. FK 에 ON DELETE CASCADE 가
        // 걸려 있어 관계만 지워도 정리되지만, 순서를 명시해 두면 나중에 CASCADE 를 떼도 안전하다.
        exec("DELETE FROM watcher_reactions WHERE notice_id IN (SELECT n.id FROM watcher_notices n " +
                "JOIN watcher_relations r ON r.id = n.relation_id WHERE r.challenge_id = :cid)", challengeId);
        exec("DELETE FROM watcher_notices WHERE relation_id IN " +
                "(SELECT id FROM watcher_relations WHERE challenge_id = :cid)", challengeId);
        exec("DELETE FROM watcher_consent_logs WHERE relation_id IN " +
                "(SELECT id FROM watcher_relations WHERE challenge_id = :cid)", challengeId);
        exec("DELETE FROM watcher_relations WHERE challenge_id = :cid", challengeId);
        exec("DELETE FROM watcher_invitations WHERE challenge_id = :cid", challengeId);
        exec("DELETE FROM challenge_delegations WHERE challenge_id = :cid", challengeId);
        // 공지·댓글은 Phase 2 이관과 함께 테이블째 사라졌다(V16) — 여기서 지울 것이 없다.
        exec("DELETE FROM challenge_invitations WHERE challenge_id = :cid", challengeId);
        exec("DELETE FROM challenge_members WHERE challenge_id = :cid", challengeId);
        exec("DELETE FROM challenges WHERE id = :cid", challengeId);
        // 네이티브 삭제는 1차 캐시를 갱신하지 않으므로, 삭제된 엔티티가 캐시에서 되살아나지 않도록 detach.
        entityManager.clear();
        // 탐색 파생 인덱스에서도 뺀다. 03:30 대조가 어차피 걷어내지만, 그때까지 목록에 유령이 뜬다.
        exploreIndexer.remove(challengeId, category);
    }

    /** 카테고리별 인기 ZSET 에서 빼려면 값을 알아야 한다. 행이 사라지기 전에 읽는다. */
    private String readCategory(UUID challengeId) {
        var rows = entityManager.createNativeQuery("SELECT category FROM challenges WHERE id = :cid")
                .setParameter("cid", uuidToBytes(challengeId))
                .getResultList();
        return rows.isEmpty() ? null : String.valueOf(rows.getFirst());
    }

    /** binary(16) UUID 바인딩 네이티브 삭제. */
    private void exec(String sql, UUID challengeId) {
        entityManager.createNativeQuery(sql)
                .setParameter("cid", uuidToBytes(challengeId))
                .executeUpdate();
    }

    private static byte[] uuidToBytes(UUID u) {
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(u.getMostSignificantBits());
        bb.putLong(u.getLeastSignificantBits());
        return bb.array();
    }
}
