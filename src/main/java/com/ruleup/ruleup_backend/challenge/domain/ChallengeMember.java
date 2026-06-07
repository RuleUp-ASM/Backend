package com.ruleup.ruleup_backend.challenge.domain;

import com.ruleup.ruleup_backend.common.AssignedIdEntity;
import com.ruleup.ruleup_backend.common.UuidGenerator;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 챌린지 멤버십 (challenge_members 테이블). 챌린지 1개 × 사용자 1명 = 1행.
 *  - uq_member(challenge_id, user_id)로 한 챌린지 1회 멤버십 (스펙 5 재참여).
 *  - 생성자는 챌린지 생성 시 OWNER/ACTIVE로 함께 등록.
 *  - 참여 신청: 솔로/기준미설정 → ACTIVE 즉시, 그룹+기준 → PENDING(운영자 승인 대기).
 * 연관관계 대신 challengeId/userId만 보유(다른 도메인과 동일 패턴).
 */
@Entity
@Table(name = "challenge_members")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChallengeMember extends AssignedIdEntity {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private UUID id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "challenge_id", nullable = false, updatable = false, length = 36)
    private UUID challengeId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "user_id", nullable = false, updatable = false, length = 36)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private MemberRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private MemberStatus status;

    @Generated(event = EventType.INSERT)
    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;            // 참여(또는 신청) 시각

    private static ChallengeMember of(UUID challengeId, UUID userId, MemberRole role, MemberStatus status) {
        ChallengeMember m = new ChallengeMember();
        m.id = UuidGenerator.generate();
        m.challengeId = challengeId;
        m.userId = userId;
        m.role = role;
        m.status = status;
        return m;
    }

    /** 생성자 등록: OWNER + 즉시 ACTIVE */
    public static ChallengeMember owner(UUID challengeId, UUID userId) {
        return of(challengeId, userId, MemberRole.OWNER, MemberStatus.ACTIVE);
    }

    /** 일반 참여: 솔로/기준미설정이면 ACTIVE, 그룹+기준이면 PENDING */
    public static ChallengeMember join(UUID challengeId, UUID userId, MemberStatus initialStatus) {
        return of(challengeId, userId, MemberRole.MEMBER, initialStatus);
    }

    public void approve() { this.status = MemberStatus.ACTIVE; }
    public void reject()  { this.status = MemberStatus.REMOVED; }
    public void leave()   { this.status = MemberStatus.LEFT; }

    /** 탈퇴/거절(LEFT·REMOVED) 후 재참여 신청 → PENDING 복귀 (스펙 5: 재참여는 status 갱신으로 처리). */
    public void rejoinAsPending() { this.status = MemberStatus.PENDING; }

    public boolean isPending() { return status == MemberStatus.PENDING; }
    public boolean isActive()  { return status == MemberStatus.ACTIVE; }
}