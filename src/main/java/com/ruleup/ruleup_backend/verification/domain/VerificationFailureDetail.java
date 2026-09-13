package com.ruleup.ruleup_backend.verification.domain;

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
import java.util.Map;
import java.util.UUID;

/**
 * 최종 {@code FAILED} 인증의 실패 상세 — <b>1:0..1</b>.
 *
 * <p>성공 행에는 만들지 않는다. 하루 6만 건 규모에서 실패 전용 컬럼을 결과 테이블에 두면
 * 성공 행마다 NULL 이 깔리고, 설명 필드가 늘어날 때마다 결과 테이블이 비대해진다.
 *
 * <p><b>실패 예정에도 만들지 않는다.</b> 그것은 저장 상태가 아니라 계산 상태라 뒤집힐 수 있다 —
 * 유예 하루 동안 늦게 도착한 신호나 이의 인용으로 완료가 되면 이 행은 존재하지 말았어야 한다.
 * 화면에 보여줄 실패 예정의 근거는 그때그때 evidence 로 계산한다({@link FailureEvidence}).
 *
 * <p>담는 값은 공통 5-8(자동화된 결정 설명)이 요구하는 넷이다 — 사유 코드 · 사람이 읽을 요약 ·
 * 판정 당시 기준값 · 실제값. <b>기준값을 스냅샷으로 남기는 것이 핵심</b>이다. GPS 반경이나 기상
 * 허용 범위는 배포 없이 조정하는 값이라, 나중 기준으로 과거 판정을 설명하면 틀린 설명이 된다.
 */
@Entity
@Table(name = "verification_failure_details")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VerificationFailureDetail {

    /** 실패한 판정 1건 — PK 이자 FK 다. 하나의 실패에 상세는 하나뿐이다. */
    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "verificationDailyId", nullable = false, updatable = false)
    private UUID verificationDailyId;

    @Column(name = "reasonCode", nullable = false, length = 40, updatable = false)
    private String reasonCode;

    /** 사용자에게 그대로 보여줄 수 있는 한 줄 — <b>비어 있으면 설명 의무를 못 지킨다</b>. */
    @Column(name = "evidenceSummary", nullable = false, length = 512, updatable = false)
    private String evidenceSummary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "expectedValue", updatable = false)
    private Map<String, Object> expectedValue;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "actualValue", updatable = false)
    private Map<String, Object> actualValue;

    @Column(name = "createdAt", nullable = false, updatable = false)
    private Instant createdAt;

    public static VerificationFailureDetail of(UUID verificationDailyId, String reasonCode,
                                               FailureEvidence evidence, Instant createdAt) {
        VerificationFailureDetail d = new VerificationFailureDetail();
        d.verificationDailyId = verificationDailyId;
        d.reasonCode = reasonCode;
        d.evidenceSummary = evidence.summary();
        d.expectedValue = evidence.expected();
        d.actualValue = evidence.actual();
        d.createdAt = createdAt;
        return d;
    }
}
