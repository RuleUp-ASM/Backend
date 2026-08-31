package com.ruleup.ruleup_backend.agreement.domain;

import com.ruleup.ruleup_backend.common.AssignedIdEntity;
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

import java.time.Instant;
import java.util.UUID;

/**
 * 동의·철회 이력 ({@code user_agreement_events}) — <b>append-only</b>.
 *
 * <p>현재 상태는 {@link UserAgreementState}가 따로 들고 있다. 그럼에도 이력을 남기는 이유는
 * 입증 책임 때문이다 — 상태 테이블만 있으면 <b>마케팅 수신을 켰다 껐다 한 이력이 사라지는데</b>,
 * 정보통신망법상 철회 이력이 남아야 한다.
 *
 * <p>상태 UPSERT와 이 행의 INSERT는 <b>반드시 한 트랜잭션</b>이다. 나뉘면 동의는 받았는데 근거가
 * 없거나, 근거는 있는데 게이트가 막는 상태가 남는다.
 */
@Entity
@Table(name = "user_agreement_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserAgreementEvent extends AssignedIdEntity {

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

    public static UserAgreementEvent of(User user, AgreementType type, boolean agreed, String version) {
        UserAgreementEvent e = new UserAgreementEvent();
        e.id = UuidGenerator.generate();
        e.user = user;
        e.agreementType = type;
        e.agreed = agreed;
        e.version = version;
        return e;
    }
}
