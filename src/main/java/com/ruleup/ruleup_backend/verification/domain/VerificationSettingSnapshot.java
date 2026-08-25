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
 * 멤버 인증 설정의 시점 스냅샷 (verification_setting_snapshots, 백엔드 테크스펙 §4-1).
 *
 * <p>확정이 귀속일 이틀 뒤로 밀리면서 과거 날짜를 다시 평가하는 창이 하루 생겼다. 그런데 앵커·대상 앱은
 * 현재 값만 들고 있어서, 유예 구간에 장소를 바꾸면 <b>어제 판정이 새 장소 기준으로 돌아간다</b> —
 * 어제 갔던 곳이 갑자기 "안 간 곳"이 된다. 그래서 "언제부터 적용된 설정인지"를 append-only 로 남긴다.
 *
 * <p>{@code effectiveFrom} 은 그 설정이 <b>판정에 쓰이기 시작하는 KST 날짜</b>다. 앵커는 변경 즉시라
 * 변경일이 그대로 들어가고, 대상 앱은 다음 날부터 적용이라 그 적용일이 들어간다.
 * 날짜 D 의 판정은 {@code effectiveFrom <= D} 중 가장 늦은 스냅샷을 쓴다.
 *
 * <p>덮어쓰지 않는다 — 과거 판정을 다시 계산할 수 있어야 기준값 조정·재판정이 성립한다.
 */
@Entity
@Table(name = "verification_setting_snapshots")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VerificationSettingSnapshot extends AssignedIdEntity {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "challengeMemberId", nullable = false, updatable = false)
    private UUID challengeMemberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false, length = 20)
    private SettingKind kind;

    /** 이 설정이 판정에 쓰이기 시작하는 KST 날짜. */
    @Column(name = "effectiveFrom", nullable = false, updatable = false)
    private LocalDate effectiveFrom;

    /** 설정 값 원본 JSON(GeoAnchor[] 또는 ScreenApp[]). */
    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "json")
    private String payload;

    @Generated(event = EventType.INSERT)
    @Column(name = "createdAt", nullable = false, updatable = false)
    private Instant createdAt;

    public static VerificationSettingSnapshot of(UUID challengeMemberId, SettingKind kind,
                                                 LocalDate effectiveFrom, String payload) {
        VerificationSettingSnapshot s = new VerificationSettingSnapshot();
        s.id = UuidGenerator.generate();
        s.challengeMemberId = challengeMemberId;
        s.kind = kind;
        s.effectiveFrom = effectiveFrom;
        s.payload = payload;
        return s;
    }
}
