package com.ruleup.ruleup_backend.verification.domain;

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
import java.util.Map;
import java.util.UUID;

/**
 * 방식별 인증 평가 결과 (VerificationMethodResult 테이블) — 방식 N줄 [자식].
 * 한 VerificationDaily에 대해 GPS/SCREEN_TIME/SLEEP 등 방식마다 1줄. 인증 스펙 §4.
 *  - uq(verificationDailyId, method)로 daily당 같은 방식 1줄.
 *  - evidence는 raw 신호가 아니라 요약 JSON만(개인정보).
 * 연관관계 대신 raw UUID(verificationDailyId)만 보유.
 */
@Entity
@Table(name = "VerificationMethodResult")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VerificationMethodResult extends AssignedIdEntity {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "verificationDailyId", nullable = false, updatable = false)
    private UUID verificationDailyId;

    @Column(name = "method", nullable = false, length = 40)
    private String method;               // GPS_PRESENCE / GPS_DISTANCE / SCREEN_TIME / WAKE / SLEEP / PHOTO / SELF_CHECK

    @Enumerated(EnumType.STRING)
    @Column(name = "polarity")
    private Polarity polarity;           // 도달형/제약형 (수동은 무관)

    @Column(name = "supported", nullable = false)
    private boolean supported = true;    // 플랫폼/권한상 지원 여부

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private VerificationStatus status = VerificationStatus.PENDING;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence")
    private Map<String, Object> evidence;   // 요약만: {dwellMinutes,usageMinutes,distanceKm,...}

    @Column(name = "lastEvaluatedAt")
    private Instant lastEvaluatedAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "createdAt", nullable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    /** 방식 평가 행 개시 (PENDING). */
    public static VerificationMethodResult create(UUID verificationDailyId, String method,
                                                  Polarity polarity, boolean supported) {
        VerificationMethodResult r = new VerificationMethodResult();
        r.id = UuidGenerator.generate();
        r.verificationDailyId = verificationDailyId;
        r.method = method;
        r.polarity = polarity;
        r.supported = supported;
        return r;
    }

    /** 평가 결과 반영(신호 평가 시점). 평가 로직은 인증 단계에서. */
    public void evaluate(VerificationStatus status, Map<String, Object> evidence, Instant lastEvaluatedAt) {
        this.status = status;
        this.evidence = evidence;
        this.lastEvaluatedAt = lastEvaluatedAt;
    }
}