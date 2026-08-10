package com.ruleup.ruleup_backend.challenge.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * 사용자당 현재 ACTIVE 참여 수 (백엔드 테크스펙 4-1 · 4-3).
 *
 * <p>동시 참여 3개 제한의 <b>락 대상 행</b>이다. 챌린지 행만 잠그면 한 사용자가 서로 다른 두 방에
 * 동시에 가입할 때 각자 다른 챌린지 행을 잡아 둘 다 "현재 2개"로 읽어 제한이 뚫린다(P0).
 * 그래서 가입·탈퇴·강퇴 전 경로에서 <b>사용자 행 → 챌린지 행</b> 순서로 잠근다(데드락 방지).
 */
@Entity
@Table(name = "user_challenge_counters")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserChallengeCounter {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "active_join_count", nullable = false)
    private int activeJoinCount;

    public static UserChallengeCounter zero(UUID userId) {
        UserChallengeCounter c = new UserChallengeCounter();
        c.userId = userId;
        c.activeJoinCount = 0;
        return c;
    }
}
