package com.ruleup.ruleup_backend.sanction.domain;

import com.ruleup.ruleup_backend.common.AssignedIdEntity;
import com.ruleup.ruleup_backend.common.UuidGenerator;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 제재 집행 이력 — 백오피스 공통 5-3이 소유하고, 온보딩 게이트가 읽는다.
 *
 * <p><b>계정 게이트의 원천이다.</b> {@code users.status} 는 SUSPENDED 하나로 줄었고, 정지의
 * 종류와 기간은 이 행이 단독으로 들고 있다.
 *
 * <h4>활성 판정이 세 갈래인 이유</h4>
 * <pre>
 *   endsAt 미래     · frozenRemainingSec null  → 정상 카운트다운
 *   endsAt null     · frozenRemainingSec 있음  → 탈퇴로 동결 (시간이 흘러도 줄지 않는다)
 *   endsAt null     · frozenRemainingSec null  → 영구 정지(BAN)
 * </pre>
 * 셋을 다 담지 않으면 동결된 계정이 백오피스에서 "제재 없음"으로 보이고, 해제 배치를
 * {@code endsAt IS NULL OR ...} 로 쓰면 <b>동결분과 영구 정지가 통째로 풀린다.</b>
 */
@Entity
@Table(name = "sanctions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Sanction extends AssignedIdEntity {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "track", nullable = false, updatable = false)
    private SanctionTrack track;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false)
    private SanctionType type;

    /** {@link SanctionType#FEATURE_SUSPENSION} 일 때만 값이 있다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "feature_code")
    private FeatureCode featureCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, updatable = false)
    private SanctionReason reasonCode;

    /** 운영자 입력 사유 — 고지 알림과 재검토 대응의 근거라 필수다. */
    @Column(name = "reason_text", nullable = false, length = 500)
    private String reasonText;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, updatable = false)
    private SanctionSource source;

    /** report_id 또는 anomaly_signal_id. 다형적이라 FK 를 걸지 않는다. */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "source_id")
    private UUID sourceId;

    @Column(name = "starts_at", nullable = false, updatable = false)
    private Instant startsAt;

    /** 해제 예정. 영구 정지와 동결 상태에서는 null 이다. */
    @Column(name = "ends_at")
    private Instant endsAt;

    /**
     * 탈퇴 시점의 잔여 초 — <b>종료 시각이 아니라 기간</b>으로 저장해야
     * 탈퇴한 채 시간을 흘려보내 제재를 소진시키는 경로가 막힌다.
     */
    @Column(name = "frozen_remaining_sec")
    private Integer frozenRemainingSec;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** 재검토는 제재당 1회. */
    @Column(name = "appeal_used", nullable = false)
    private boolean appealUsed;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "operator_id")
    private UUID operatorId;

    /** 필수(A) 고지 발행 시각 — null 이면 "고지 없이 집행된 직권 제재" 가드레일 위반이다. */
    @Column(name = "notified_at")
    private Instant notifiedAt;

    public static Sanction of(UUID userId, SanctionTrack track, SanctionType type, FeatureCode featureCode,
                              SanctionReason reasonCode, String reasonText, SanctionSource source,
                              UUID sourceId, UUID operatorId, Instant startsAt, Instant endsAt) {
        Sanction s = new Sanction();
        s.id = UuidGenerator.generate();
        s.userId = userId;
        s.track = track;
        s.type = type;
        s.featureCode = featureCode;
        s.reasonCode = reasonCode;
        s.reasonText = reasonText;
        s.source = source;
        s.sourceId = sourceId;
        s.operatorId = operatorId;
        s.startsAt = startsAt;
        // 영구 정지는 해제일이 없다. 호출자가 실수로 값을 넣어도 여기서 잘라낸다.
        s.endsAt = type.isPermanent() ? null : endsAt;
        s.appealUsed = false;
        return s;
    }

    /**
     * 현재 효력이 있는지. 세 경우를 모두 담는다 — 하나라도 빠뜨리면 동결된 계정이
     * "제재 없음"으로 보이거나 영구 정지가 조용히 풀린다.
     */
    public boolean isActiveAt(Instant now) {
        if (revokedAt != null) return false;
        if (frozenRemainingSec != null) return true;          // 동결 — 시간이 흐르지 않는다
        if (endsAt == null) return true;                      // 영구 정지
        return endsAt.isAfter(now);
    }

    /** 탈퇴 — 잔여 기간을 얼린다. 영구 정지와 이미 끝난 제재는 대상이 아니다. */
    public void freeze(Instant now) {
        if (revokedAt != null || endsAt == null) return;      // 영구 정지·이미 동결
        long remaining = Duration.between(now, endsAt).toSeconds();
        if (remaining <= 0) return;                            // 이미 기간이 지난 제재
        this.frozenRemainingSec = (int) Math.min(remaining, Integer.MAX_VALUE);
        this.endsAt = null;
    }

    /** 재가입(복원) — 얼려둔 기간만큼 다시 카운트다운을 시작한다. */
    public void thaw(Instant now) {
        if (frozenRemainingSec == null) return;
        this.endsAt = now.plusSeconds(frozenRemainingSec);
        this.frozenRemainingSec = null;
    }

    /** 재검토 인용 해제 — 원본을 지우지 않고 관계만 남긴다. */
    public void revoke(Instant at) {
        this.revokedAt = at;
    }

    public void markAppealUsed() {
        this.appealUsed = true;
    }

    public void markNotified(Instant at) {
        this.notifiedAt = at;
    }

    /** 테스트에서 "기간이 지난 제재"를 만들기 위한 통로. 운영 경로에서는 쓰지 않는다. */
    public void forceEndsAtForTest(Instant at) {
        this.endsAt = at;
    }
}
