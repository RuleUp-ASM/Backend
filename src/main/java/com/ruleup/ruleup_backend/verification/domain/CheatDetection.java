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
import java.util.Map;
import java.util.UUID;

/**
 * 이상패턴 탐지로 <b>확정된</b> 부정행위 1건 (공통 5-3).
 *
 * <p>단일 신호의 이상은 여기 오지 않는다 — 그건 {@link SignalExclusion} 에서 끝난다.
 * 여러 인증 건에 걸친 누적 패턴이 확인됐을 때만 확정이고, <b>확정 1건이 곧 강퇴·영구 차단·−50</b>이다.
 * 그래서 이 행은 「몇 번째인가」를 세는 카운터가 아니라 <b>무엇을 근거로 확정했는가</b>의 기록이다.
 *
 * <p>이력은 지우지 않고 계정에 누적 보존한다. 다만 이 값으로 계정 제재가 자동 승격되지는 않는다 —
 * 계정 단위 제재는 운영자가 이 이력을 보고 직권으로 판단한다.
 */
@Entity
@Table(name = "cheat_detections")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CheatDetection {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "userId", nullable = false, updatable = false)
    private UUID userId;

    /** 집계 단위 — 영구 차단은 계정이 아니라 이 방에 걸린다. */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "challengeId", nullable = false, updatable = false)
    private UUID challengeId;

    /** 검출로 무효가 된 판정. <b>UNIQUE</b> 라 같은 판정으로 두 번 확정되지 않는다. */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "verificationDailyId", nullable = false, updatable = false)
    private UUID verificationDailyId;

    /** 탐지 근거 — 반복 위조·불가능한 이동량·자동화 도구 패턴. 설명할 수 없는 제재는 남기지 않는다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pattern", nullable = false, updatable = false)
    private Map<String, Object> pattern;

    @Column(name = "detectedAt", nullable = false, updatable = false)
    private Instant detectedAt;

    public static CheatDetection of(UUID userId, UUID challengeId, UUID verificationDailyId,
                                    Map<String, Object> pattern, Instant detectedAt) {
        CheatDetection d = new CheatDetection();
        d.id = UuidGenerator.generate();
        d.userId = userId;
        d.challengeId = challengeId;
        d.verificationDailyId = verificationDailyId;
        d.pattern = (pattern == null || pattern.isEmpty()) ? Map.of("note", "UNSPECIFIED") : pattern;
        d.detectedAt = detectedAt;
        return d;
    }
}
