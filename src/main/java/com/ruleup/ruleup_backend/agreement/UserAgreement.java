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
 * 약관 동의 이력 (UserAgreement 테이블). User와 N:1.
 * "누가/어떤 약관/어떤 버전에/언제" 동의했는지 별도 행으로 추적.
 */
@Entity
@Table(name = "UserAgreement")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserAgreement extends AssignedIdEntity {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "userId", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "agreementType", nullable = false)
    private AgreementType agreementType;

    @Column(name = "version", nullable = false)
    private String version;

    @Column(name = "revokedAt")
    private Instant revokedAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "agreedAt", nullable = false, updatable = false)
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