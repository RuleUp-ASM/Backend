package com.ruleup.ruleup_backend.watcher.domain;

import com.ruleup.ruleup_backend.common.AssignedIdEntity;
import com.ruleup.ruleup_backend.common.UuidGenerator;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 초대 토큰 ({@code watcher_invitations}).
 *
 * <p><b>카카오톡으로 외부에 나가므로 URL 에 개인정보를 담지 않고 원본 토큰도 저장하지 않는다.</b>
 * 해시만 보관하므로 DB 가 유출돼도 유효한 초대 링크를 복원할 수 없다.
 *
 * <p>룰업은 이 초대를 <b>직접 보내지 않는다.</b> 사용자 본인 명의의 카카오톡 공유로 나가므로
 * 사적 통신이 되고, 동의하지 않은 외부인에게 사업자가 먼저 닿는 상황이 생기지 않는다.
 */
@Entity
@Table(name = "watcher_invitations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WatcherInvitation extends AssignedIdEntity {

    /** 발급 + 7일. */
    public static final Duration TTL = Duration.ofDays(7);

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** SHA-256 — <b>원본 미저장</b>. */
    @Column(name = "token_hash", nullable = false, unique = true, length = 64, updatable = false)
    private String tokenHash;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "challenge_id", nullable = false, updatable = false)
    private UUID challengeId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "inviter_user_id", nullable = false, updatable = false)
    private UUID inviterUserId;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    /** null 이면 미수락. */
    @Column(name = "accepted_at")
    private Instant acceptedAt;

    /**
     * 생성자에게 만료 알림을 보낸 시각 — 중복 발송 방지.
     * <b>감시자 후보에게는 어떤 알림도 보내지 않는다</b> — 아직 동의하지 않은 외부인이다.
     */
    @Column(name = "expiry_notified_at")
    private Instant expiryNotifiedAt;

    public static WatcherInvitation issue(UUID challengeId, UUID inviterUserId,
                                          String tokenHash, Instant now) {
        WatcherInvitation i = new WatcherInvitation();
        i.id = UuidGenerator.generate();
        i.tokenHash = tokenHash;
        i.challengeId = challengeId;
        i.inviterUserId = inviterUserId;
        i.expiresAt = now.plus(TTL);
        return i;
    }

    public boolean isExpired(Instant now) {
        return expiresAt.isBefore(now);
    }

    public void markAccepted(Instant at) {
        if (acceptedAt == null) this.acceptedAt = at;
    }

    public void markExpiryNotified(Instant at) {
        this.expiryNotifiedAt = at;
    }
}
