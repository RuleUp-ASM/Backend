package com.ruleup.ruleup_backend.verification.domain;

import com.ruleup.ruleup_backend.common.AssignedIdEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 수신한 인증 신호 원본 (verification_signals 테이블, 백엔드 테크스펙 §4-3).
 *
 * <p>클라가 미리 집계한 요약이 아니라 <b>원본 그대로</b> 보관한다. 정확도·mock 여부·기록 방식·출처 같은
 * 부정행위 검증의 근거가 원본에만 있고, GPS 반경·기상 허용 범위 같은 기준값을 배포 없이 조정하려면
 * 과거 신호로 다시 계산할 수 있어야 하기 때문이다.
 *
 * <p>uq(userId, dedupKey)가 영속 멱등을 보장한다. 멱등을 평가기 안의 상태나 evidence 에만 맡기면
 * 평가기마다 다시 구현해야 하고 한 곳이라도 빠지면 재전송이 사용 시간을 두 배로 만든다.
 *
 * <p>확정된 날짜의 신호도 거절하지 않고 저장한다 — 판정에 쓰지 않을 뿐 이상탐지 자료로는 쓴다.
 */
@Entity
@Table(name = "verification_signals")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VerificationSignal extends AssignedIdEntity {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "userId", nullable = false, updatable = false)
    private UUID userId;

    /** 멱등 키 — recordId 가 있으면 그것으로, 없으면 신호 내용 전체로 만든 해시. */
    @Column(name = "dedupKey", nullable = false, updatable = false, length = 64)
    private String dedupKey;

    @Column(name = "signalType", nullable = false, updatable = false, length = 32)
    private String signalType;

    /** 신호 관측 시각(클라 선언). 파싱 불가면 null — 날짜 귀속은 평가기가 신호 내용으로 다시 판단한다. */
    @Column(name = "observedAt", updatable = false)
    private Instant observedAt;

    @Column(name = "receivedAt", nullable = false, updatable = false)
    private Instant receivedAt;

    /** 신호 원본(JSON). 계약에 선언된 필드가 보존 대상이다. */
    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "json")
    private String payload;
}
