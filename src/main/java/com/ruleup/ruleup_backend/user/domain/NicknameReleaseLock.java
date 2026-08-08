package com.ruleup.ruleup_backend.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 변경으로 버려진 닉네임의 1주일 잠금 (회원 정책 §3 "이전 닉네임 처리" — 사칭 방지).
 * 잠금 기간 동안 <b>타인은</b> 등록·변경에 쓸 수 없고, 버린 본인은 되돌릴 수 있다.
 *
 * <p>닉네임 자체가 PK다 — 같은 닉네임의 잠금은 언제나 최신 1건만 의미가 있으므로
 * 이력 테이블이 아니라 현재 잠금 상태를 들고 있는 테이블이다.
 */
@Entity
@Table(name = "nickname_release_locks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NicknameReleaseLock {

    @Id
    @Column(name = "nickname", nullable = false, updatable = false, length = 12)
    private String nickname;

    /** 이 닉네임을 버린 사용자 — 본인 예외 판정용. 탈퇴로 유저가 사라지면 NULL. */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "released_by")
    private UUID releasedBy;

    @Column(name = "released_at", nullable = false)
    private Instant releasedAt;

    @Column(name = "locked_until", nullable = false)
    private Instant lockedUntil;

    public static NicknameReleaseLock of(String nickname, UUID releasedBy, Instant now) {
        NicknameReleaseLock lock = new NicknameReleaseLock();
        lock.nickname = nickname;
        lock.relock(releasedBy, now);
        return lock;
    }

    /**
     * 잠금 갱신 — 같은 닉네임이 다시 버려지면 새로 버린 사람 기준으로 7일을 다시 센다.
     * (이전 소유자의 본인 예외가 새 소유자에게 넘어가지 않게 releasedBy 도 함께 바꾼다)
     */
    public void relock(UUID releasedBy, Instant now) {
        this.releasedBy = releasedBy;
        this.releasedAt = now;
        this.lockedUntil = now.plus(NicknamePolicy.RELEASE_LOCK);
    }

    public boolean isActiveAt(Instant now) {
        return now.isBefore(lockedUntil);
    }

    /** 잠금이 이 사용자에게 적용되는지 — 버린 본인에게는 걸지 않는다(정책 §3 "타인이"). */
    public boolean blocks(UUID candidateId, Instant now) {
        if (!isActiveAt(now)) return false;
        return releasedBy == null || !releasedBy.equals(candidateId);
    }
}
