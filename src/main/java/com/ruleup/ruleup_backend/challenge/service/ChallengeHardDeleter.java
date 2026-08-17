package com.ruleup.ruleup_backend.challenge.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * 챌린지 하드 삭제 — 방 데이터(잔디·공지·감시자·멤버)를 FK 안전 순서로 물리 삭제한다.
 * 자동 삭제 배치(만료·유령방)가 이력 스냅샷 적재 후 호출한다(방장 수동 삭제는 폐기).
 * 인증 기록 원본(VerificationDaily)·이의·통계(RoutineOutcome)는 보존한다(소프트 참조 — V6).
 * 대표 이미지는 URL로만 참조하므로 DB 행 삭제와 무관하게 스토리지에 남는다 — 호출부가 imageUrl을 로깅한다.
 */
@Component
public class ChallengeHardDeleter {

    @PersistenceContext
    private EntityManager entityManager;

    /** 챌린지 challengeId 와 그 자식 행 전부를 물리 삭제한다. 호출부의 @Transactional 안에서 실행. */
    public void hardDelete(UUID challengeId) {
        // 대기 중인 영속 변경(알림 insert 등)을 먼저 DB에 반영(네이티브 삭제가 최신 상태를 지우도록).
        entityManager.flush();
        // 인증 기록 원본(VerificationDaily·MethodResult)·이의(Objection)·통계(RoutineOutcome)는 보존한다
        // (자동 삭제 스펙: 잔디는 삭제, 인증 원본·통계값 보존 — V6 소프트 참조 전환).
        exec("DELETE FROM WatcherNotification WHERE challengeId = :cid", challengeId);
        exec("DELETE FROM WatcherOtp WHERE invitationId IN " +
                "(SELECT id FROM WatcherInvitation WHERE challengeId = :cid)", challengeId);
        exec("DELETE FROM Watcher WHERE challengeId = :cid", challengeId);
        exec("DELETE FROM WatcherInvitation WHERE challengeId = :cid", challengeId);
        exec("DELETE FROM challenge_delegations WHERE challenge_id = :cid", challengeId);
        // 공지·댓글은 Phase 2 이관과 함께 테이블째 사라졌다(V16) — 여기서 지울 것이 없다.
        exec("DELETE FROM challenge_invitations WHERE challenge_id = :cid", challengeId);
        exec("DELETE FROM challenge_members WHERE challenge_id = :cid", challengeId);
        exec("DELETE FROM challenges WHERE id = :cid", challengeId);
        // 네이티브 삭제는 1차 캐시를 갱신하지 않으므로, 삭제된 엔티티가 캐시에서 되살아나지 않도록 detach.
        entityManager.clear();
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
