package com.ruleup.ruleup_backend.challenge.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * 챌린지 하드 삭제(§8-4) — 챌린지와 자식 행을 FK 안전 순서로 물리 삭제한다.
 * 방장 삭제(ChallengeService)와 이미지 모더레이션 마감(ChallengeModerationCloseService)이 공유한다.
 * 삭제는 참여자 0명(방장만)일 때만 호출되므로 대상 데이터량은 작다.
 * 대표 이미지(S3)는 URL로만 참조하므로 DB 행 삭제와 무관하게 S3에 남는다 — 삭제 전 호출부가 imageUrl을 감사 로깅한다.
 */
@Component
public class ChallengeHardDeleter {

    @PersistenceContext
    private EntityManager entityManager;

    /** 챌린지 challengeId 와 그 자식 행 전부를 물리 삭제한다. 호출부의 @Transactional 안에서 실행. */
    public void hardDelete(UUID challengeId) {
        // 대기 중인 영속 변경(알림 insert 등)을 먼저 DB에 반영(네이티브 삭제가 최신 상태를 지우도록).
        entityManager.flush();
        exec("DELETE FROM VerificationMethodResult WHERE verificationDailyId IN " +
                "(SELECT id FROM VerificationDaily WHERE challengeId = :cid)", challengeId);
        exec("DELETE FROM VerificationDaily WHERE challengeId = :cid", challengeId);
        exec("DELETE FROM Objection WHERE challengeId = :cid", challengeId);
        exec("DELETE FROM RoutineOutcome WHERE challengeId = :cid", challengeId);
        exec("DELETE FROM WatcherNotification WHERE challengeId = :cid", challengeId);
        exec("DELETE FROM WatcherOtp WHERE invitationId IN " +
                "(SELECT id FROM WatcherInvitation WHERE challengeId = :cid)", challengeId);
        exec("DELETE FROM Watcher WHERE challengeId = :cid", challengeId);
        exec("DELETE FROM WatcherInvitation WHERE challengeId = :cid", challengeId);
        exec("DELETE FROM ChallengeDelegation WHERE challengeId = :cid", challengeId);
        exec("DELETE FROM ChallengeMember WHERE challengeId = :cid", challengeId);
        exec("DELETE FROM Challenge WHERE id = :cid", challengeId);
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
