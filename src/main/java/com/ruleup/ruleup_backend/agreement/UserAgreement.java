package com.ruleup.ruleup_backend.agreement;

import com.ruleup.ruleup_backend.common.UuidGenerator;
import com.ruleup.ruleup_backend.user.User;
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
 * 약관 동의 이력 (user_agreements 테이블). User와 N:1.
 * "누가/어떤 약관/어떤 버전에/언제" 동의했는지 별도 행으로 추적.
 */
@Entity
@Table(name = "user_agreements")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserAgreement extends AssignedIdEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "agreement_type", nullable = false, columnDefinition = "agreement_type")
    private AgreementType agreementType;

    @Column(name = "version", nullable = false)
    private String version;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "agreed_at", nullable = false, updatable = false)
    private Instant agreedAt;

    public static UserAgreement agree(User user, AgreementType type, String version) {
        UserAgreement a = new UserAgreement();
        a.id = UuidGenerator.generate();
        a.user = user;
        a.agreementType = type;
        a.version = version;
        return a;
    }

    public void revoke() { this.revokedAt = Instant.now(); }
}