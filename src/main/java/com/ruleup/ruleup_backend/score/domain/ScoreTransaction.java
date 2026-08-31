package com.ruleup.ruleup_backend.score.domain;

import com.ruleup.ruleup_backend.common.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * 점수 변동 원장 1행. <b>append-only</b> — 정정도 새 행으로 쌓는다.
 *
 * <p>마이페이지는 이 원장에서 두 화면을 파생시킨다.
 * <ul>
 *   <li><b>최근 변동 10건</b> — {@code reason}·{@code challengeId}·{@code amount}</li>
 *   <li><b>티어 히스토리</b> — 월별 마지막 행의 {@code balanceAfter} 가 곧 월말 스냅샷이다</li>
 * </ul>
 *
 * <p>스냅샷 테이블을 따로 두지 않은 이유가 여기 있다. 이의는 자동 인용이라 과거 판정이 수시로
 * 뒤집히는데, 물질화한 스냅샷은 그때마다 과거 월을 되짚어 고쳐야 한다. 원장에서 파생하면
 * 정정 행이 하나 쌓이는 것으로 재계산이 끝난다.
 *
 * <p>쓰기는 점수 산식 스택 소관이다. 이 모듈은 읽기만 한다.
 */
@Entity
@Table(name = "score_transactions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ScoreTransaction extends AssignedIdEntity {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** 양수=증가, 음수=감소. 0 은 저장하지 않는다(CHECK). */
    @Column(name = "amount", nullable = false, updatable = false)
    private long amount;

    /** 이 변동을 적용한 뒤의 총점 — 월말 스냅샷과 역대 최고의 원천이다. */
    @Column(name = "balance_after", nullable = false, updatable = false)
    private long balanceAfter;

    /** 화면에 표시하는 사건 종류. 값을 채우는 것은 점수 산식 스택의 몫이라 아직 null 일 수 있다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "reason")
    private ScoreReason reason;

    /** 변동을 일으킨 챌린지. 계정 단위 변동(운영자 조정 등)이면 null. */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "challenge_id")
    private UUID challengeId;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Override
    public UUID getId() { return id; }
}
