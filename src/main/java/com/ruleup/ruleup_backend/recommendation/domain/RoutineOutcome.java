package com.ruleup.ruleup_backend.recommendation.domain;

import com.ruleup.ruleup_backend.common.AssignedIdEntity;
import com.ruleup.ruleup_backend.common.UuidGenerator;
import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.verification.domain.VerifiedVia;
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
 * 루틴 하루 확정 결과(RoutineOutcome) — 추천 학습용 이력.
 *
 * <p>목적: "성공한 사람의 루틴을 비슷한 카테고리의 실패한 사람에게 추천"하기 위한 원천 데이터.
 * verification 이 확정한 하루 판정(SUCCESS/FAILED)을 사용자·템플릿·카테고리와 함께 비정규화해 쌓는다.
 * 지금은 <b>수집만</b> 한다(추천 쿼리/엔드포인트는 데이터가 쌓인 뒤 별도 단계).
 *
 * <p>적재는 {@code RoutineOutcomeCollector} 일배치가 {@code VerificationDaily} 종결행을 훑어 upsert.
 *  - uq(challengeId, userId, targetDate)로 멤버×날짜 1행(재수집 멱등, PENDING→SUCCESS 정정 반영).
 *  - templateId 는 직접입력 챌린지면 null. category 는 챌린지 카테고리 문자열 스냅샷.
 *  - FK 없이 raw UUID + 값 스냅샷(다른 도메인과 동일 패턴).
 */
@Entity
@Table(name = "RoutineOutcome",
        uniqueConstraints = @UniqueConstraint(name = "uqRoutineOutcomeMemberDate",
                columnNames = {"challengeId", "userId", "targetDate"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RoutineOutcome extends AssignedIdEntity {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "userId", nullable = false, updatable = false)
    private UUID userId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "challengeId", nullable = false, updatable = false)
    private UUID challengeId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "challengeMemberId", nullable = false, updatable = false)
    private UUID challengeMemberId;

    @Column(name = "templateId")
    private Long templateId;              // 직접입력이면 null

    @Column(name = "category", length = 20)
    private String category;              // 챌린지 카테고리 스냅샷

    @Column(name = "targetDate", nullable = false, updatable = false)
    private LocalDate targetDate;         // KST 기준

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private VerificationStatus status;    // SUCCESS / FAILED (종결만)

    @Enumerated(EnumType.STRING)
    @Column(name = "verifiedVia", length = 20)
    private VerifiedVia verifiedVia;      // AUTO / MANUAL / MANUAL_FALLBACK (없으면 null)

    @Column(name = "failureReason", length = 40)
    private String failureReason;         // 실패 사유 코드(성공이면 null)

    @Column(name = "confirmedAt", nullable = false)
    private Instant confirmedAt;          // 확정 시각(= VerificationDaily.verifiedAt) — 고수위 워터마크

    @Generated(event = EventType.INSERT)
    @Column(name = "createdAt", nullable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    public static RoutineOutcome record(UUID userId, UUID challengeId, UUID challengeMemberId,
                                        Long templateId, String category, LocalDate targetDate,
                                        VerificationStatus status, VerifiedVia verifiedVia,
                                        String failureReason, Instant confirmedAt) {
        RoutineOutcome o = new RoutineOutcome();
        o.id = UuidGenerator.generate();
        o.userId = userId;
        o.challengeId = challengeId;
        o.challengeMemberId = challengeMemberId;
        o.templateId = templateId;
        o.category = category;
        o.targetDate = targetDate;
        o.status = status;
        o.verifiedVia = verifiedVia;
        o.failureReason = failureReason;
        o.confirmedAt = confirmedAt;
        return o;
    }

    /** 재수집 시 갱신(예: PENDING 이 나중 확정되며 상태·확정시각 정정). */
    public void update(VerificationStatus status, VerifiedVia verifiedVia, String failureReason, Instant confirmedAt) {
        this.status = status;
        this.verifiedVia = verifiedVia;
        this.failureReason = failureReason;
        this.confirmedAt = confirmedAt;
    }
}
