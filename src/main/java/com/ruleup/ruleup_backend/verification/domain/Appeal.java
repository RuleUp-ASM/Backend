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
import java.time.LocalDate;
import java.util.UUID;

/**
 * 인증 이의 (verification_appeals 테이블, 인증 정책 §5).
 *
 * <p><b>상태가 없다.</b> 접수된 이의는 전부 인용된 이의다 — 형식 요건을 통과하지 못한 요청은 아예 접수하지 않고,
 * 통과한 요청은 판정 없이 즉시 인용되기 때문이다. 그래서 PENDING·REJECTED 도, 처리자·처리 사유도 없다.
 *
 * <p>uq(verificationDailyId)가 "실패 결과 하나에 이의 하나"를 보장한다 — 같은 실패에 대한 재시도가
 * 정정과 점수를 두 번 적용하지 못하게 막는 멱등 앵커다.
 *
 * <p>행 자체가 남용 이상탐지의 입력이다(사용자별 빈도·실패 대비 비율·동일 사유/이미지 반복).
 * 이상탐지는 인용 이후 비동기로 돌며 이미 인용된 결과를 되돌리지 않는다.
 */
@Entity
@Table(name = "verification_appeals")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Appeal extends AssignedIdEntity {

    /** 사유 최소 길이. 결정적인 형식 요건이라 서버가 단일 기준으로 판정한다. */
    public static final int MIN_REASON_LENGTH = 10;

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** 이의 대상 인증(= API 의 verificationId). 실패 결과 기준 멱등의 앵커. */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "verificationDailyId", nullable = false, updatable = false)
    private UUID verificationDailyId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "challengeId", nullable = false, updatable = false)
    private UUID challengeId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "challengeMemberId", nullable = false, updatable = false)
    private UUID challengeMemberId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "userId", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "targetDate", nullable = false, updatable = false)
    private LocalDate targetDate;

    @Column(name = "reason", nullable = false, length = 1000)
    private String reason;

    /** 증빙 사진(선택). 저장만 하고 진위 확인에 쓰지 않는다 — 이상탐지의 "동일 이미지 반복" 입력이다. */
    @Column(name = "imageUrl", length = 500)
    private String imageUrl;

    /** 인용 시각. 접수 = 인용이라 접수 시각과 같다. */
    @Column(name = "acceptedAt", nullable = false, updatable = false)
    private Instant acceptedAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "createdAt", nullable = false, updatable = false)
    private Instant createdAt;

    /** 형식 요건을 통과한 이의를 인용 상태로 접수한다. */
    public static Appeal accept(UUID verificationDailyId, UUID challengeId, UUID challengeMemberId,
                                UUID userId, LocalDate targetDate, String reason, String imageUrl,
                                Instant acceptedAt) {
        Appeal a = new Appeal();
        a.id = UuidGenerator.generate();
        a.verificationDailyId = verificationDailyId;
        a.challengeId = challengeId;
        a.challengeMemberId = challengeMemberId;
        a.userId = userId;
        a.targetDate = targetDate;
        a.reason = reason;
        a.imageUrl = imageUrl;
        a.acceptedAt = acceptedAt;
        return a;
    }

    /** 사유가 형식 요건을 충족하는지. 공백만 있는 값은 사유가 아니다. */
    public static boolean isValidReason(String reason) {
        return reason != null && reason.trim().length() >= MIN_REASON_LENGTH;
    }
}
