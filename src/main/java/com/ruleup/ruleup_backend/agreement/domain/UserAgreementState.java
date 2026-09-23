package com.ruleup.ruleup_backend.agreement.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 동의 <b>현재 상태</b> ({@code user_agreement_states}) — 유저당 최대 7행 고정.
 *
 * <p>개정·철회를 반복해도 행이 늘지 않고 UPSERT로 덮어쓴다. 이 테이블이 핫 경로이기 때문이다 —
 * 위치·건강 인증 제출마다 개별 동의 여부를 확인해 403 {@code AGREEMENT_REQUIRED}를 판정하는데,
 * PK 조회 한 번으로 끝나야 이력이 아무리 쌓여도 느려지지 않는다.
 *
 * <p>{@code version}이 null 이면 <b>한 번도 동의한 적 없음</b>이다. {@code agreed=false} 인데
 * version 이 있으면 동의 후 철회한 것이라 의미가 다르다.
 */
@Entity
@Table(name = "user_agreement_states")
@IdClass(UserAgreementState.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserAgreementState {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "agreement_type", nullable = false, updatable = false)
    private AgreementType agreementType;

    @Column(name = "agreed", nullable = false)
    private boolean agreed;

    /** 현재 동의한 버전. 개정 재동의 판정이 이 값 하나로 끝난다. */
    @Column(name = "version", nullable = false)
    private String version;

    @Column(name = "agreed_at", nullable = false)
    private Instant agreedAt;

    public static UserAgreementState of(UUID userId, AgreementType type, boolean agreed,
                                        String version, Instant agreedAt) {
        UserAgreementState s = new UserAgreementState();
        s.userId = userId;
        s.agreementType = type;
        s.agreed = agreed;
        s.version = version;
        s.agreedAt = agreedAt;
        return s;
    }

    /** 동의·철회 반영 — 행을 늘리지 않고 덮어쓴다. */
    public void apply(boolean agreed, String version, Instant at) {
        this.agreed = agreed;
        this.version = version;
        this.agreedAt = at;
    }

    /** 테스트·마이그레이션 보조 — 버전만 바꾼 사본 의미로 같은 행을 갱신한다. */
    public UserAgreementState withVersion(String version) {
        this.version = version;
        return this;
    }

    /** {@code (user_id, agreement_type)} 복합 PK. */
    public record Key(UUID userId, AgreementType agreementType) implements Serializable {
        @Override
        public boolean equals(Object o) {
            return o instanceof Key k
                    && Objects.equals(userId, k.userId)
                    && agreementType == k.agreementType;
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, agreementType);
        }
    }
}
