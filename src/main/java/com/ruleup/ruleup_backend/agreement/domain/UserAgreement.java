package com.ruleup.ruleup_backend.agreement.domain;

import com.ruleup.ruleup_backend.common.UuidGenerator;
import com.ruleup.ruleup_backend.user.domain.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;
import com.ruleup.ruleup_backend.common.AssignedIdEntity;

import java.time.Instant;
import java.util.UUID;

/**
 * 약관 동의/철회 이력 (user_agreements 테이블). User와 N:1, append-only.
 * "누가/어떤 약관/어떤 버전에/언제 동의(또는 철회)했는지"를 행으로 누적하고,
 * 현재 상태는 (user, type)별 최신 행으로 조회한다.
 */
@Entity
@Table(name = "user_agreements")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserAgreement extends AssignedIdEntity {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "agreement_type", nullable = false)
    private AgreementType agreementType;

    /** true=동의, false=철회. */
    @Column(name = "agreed", nullable = false)
    private boolean agreed;

    @Column(name = "version", nullable = false)
    private String version;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static UserAgreement of(User user, AgreementType type, boolean agreed, String version) {
        UserAgreement a = new UserAgreement();
        a.id = UuidGenerator.generate();
        a.user = user;
        a.agreementType = type;
        a.agreed = agreed;
        a.version = version;
        return a;
    }
}
