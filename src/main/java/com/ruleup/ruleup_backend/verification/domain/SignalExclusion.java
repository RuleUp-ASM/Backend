package com.ruleup.ruleup_backend.verification.domain;

import com.ruleup.ruleup_backend.common.UuidGenerator;
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
import java.util.UUID;

/**
 * 신호 위생 배제 1건 — <b>제재가 아니라 이상패턴 탐지의 입력</b>이다(공통 5-3).
 *
 * <p>스펙이 검증을 두 층으로 갈랐다. 단일 신호의 단순 이상(VPN · 좌표 튀김 · 직접 입력)은
 * <b>그 신호만 빼고 나머지로 판정</b>하고 부정행위로 확정하지 않는다. 회사 VPN 을 켜 둔 사람과
 * 위치를 속이는 사람을 신호 하나로는 구분할 수 없기 때문이다. 확정은 여러 건에 걸친 누적
 * 패턴을 보는 이상패턴 탐지 층이 한다 — 이 행들이 그 층의 입력이다.
 *
 * <p>원본을 복사하지 않는다. 누가·어떤 신호·왜·언제 넷만 남긴다(백엔드 4-1-1).
 */
@Entity
@Table(name = "signal_exclusions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SignalExclusion {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "userId", nullable = false, updatable = false)
    private UUID userId;

    /** 배제가 영향을 준 판정. <b>게이트 단계는 챌린지별 판정 전</b>이라 null 이다. */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "verificationDailyId", updatable = false)
    private UUID verificationDailyId;

    @Column(name = "signalType", nullable = false, length = 20, updatable = false)
    private String signalType;

    @Column(name = "reason", nullable = false, length = 20, updatable = false)
    private String reason;

    /** 같은 사유·타입을 묶어 센 수. 신호 하나에 한 행이면 sync 한 번에 수백 행이 된다. */
    @Column(name = "signalCount", nullable = false, updatable = false)
    private int signalCount;

    @Column(name = "excludedAt", nullable = false, updatable = false)
    private Instant excludedAt;

    public static SignalExclusion of(UUID userId, UUID verificationDailyId, String signalType,
                                     SignalExclusionReason reason, int signalCount, Instant at) {
        SignalExclusion e = new SignalExclusion();
        e.id = UuidGenerator.generate();
        e.userId = userId;
        e.verificationDailyId = verificationDailyId;
        e.signalType = signalType;
        e.reason = reason.name();
        e.signalCount = Math.max(signalCount, 1);
        e.excludedAt = at;
        return e;
    }
}
