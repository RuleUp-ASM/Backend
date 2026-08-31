package com.ruleup.ruleup_backend.admin.domain;

import com.ruleup.ruleup_backend.common.AssignedIdEntity;
import com.ruleup.ruleup_backend.common.UuidGenerator;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 장애 구제 범위 ({@code outage_reliefs}).
 *
 * <p><b>성공 처리가 아니라 분모에서 제외</b>하는 중립 처리다. 성공으로 만들면 장애를 겪지 않은
 * 사람과의 형평이 깨지고, 실패로 두면 서비스 책임을 사용자가 진다. 성공률·스트릭·사이클이
 * 모두 이 기간을 없던 것으로 계산한다.
 */
@Entity
@Table(name = "outage_reliefs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutageRelief extends AssignedIdEntity {

    /** ALL 또는 인증 수단별 부분 구제. */
    public enum Scope { ALL, VERIFY_TYPE }

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "period_start", nullable = false, updatable = false)
    private Instant periodStart;

    @Column(name = "period_end", nullable = false, updatable = false)
    private Instant periodEnd;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 30, updatable = false)
    private Scope scope;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "operator_id", nullable = false, updatable = false)
    private UUID operatorId;

    /** 영향 판정 건수 — 적용 전에 미리 보여주고 확인받은 값이다. */
    @Column(name = "affected_count")
    private Integer affectedCount;

    @Column(name = "applied_at", nullable = false, updatable = false)
    private Instant appliedAt;

    public static OutageRelief of(Instant start, Instant end, Scope scope, UUID operatorId,
                                  Integer affectedCount, Instant at) {
        OutageRelief r = new OutageRelief();
        r.id = UuidGenerator.generate();
        r.periodStart = start;
        r.periodEnd = end;
        r.scope = scope;
        r.operatorId = operatorId;
        r.affectedCount = affectedCount;
        r.appliedAt = at;
        return r;
    }
}
