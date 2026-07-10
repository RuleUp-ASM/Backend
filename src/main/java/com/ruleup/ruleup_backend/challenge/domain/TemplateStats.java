package com.ruleup.ruleup_backend.challenge.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 템플릿 단위 탐색 집계(탐색 스펙 §3.2.1·§3.2.3). RoutineTemplate(정적 카탈로그)와 1:1, FK 없이 앱 검증.
 * 배치가 파생 챌린지·완료 회차를 재집계해 통째로 재작성한다(질의 시점 집계 없음).
 *  - usageCount            : 파생된 모든 챌린지의 현재 참여자 수 합.
 *  - completedParticipants : 완료된 회차의 누적 참여자 수(표본). >10 일 때만 completionRate 노출.
 *  - completionRate        : 완주자 / 완료 참여자 (0~1). 표본 부족 시 null.
 */
@Entity
@Table(name = "TemplateStats")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TemplateStats {

    @Id
    @Column(name = "templateId", nullable = false)
    private Long templateId;

    @Column(name = "usageCount", nullable = false)
    private long usageCount;

    @Column(name = "completedParticipants", nullable = false)
    private long completedParticipants;

    @Column(name = "completionRate", precision = 5, scale = 4)
    private BigDecimal completionRate;      // null = 표본 부족(누적 완료 참여자 ≤ 10)

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    /** 표시 하한: 누적 완료 참여자가 이 값을 초과해야 completionRate 를 낸다(§3.2.3). */
    public static final long MIN_COMPLETED_PARTICIPANTS = 10;

    public static TemplateStats rebuilt(Long templateId, long usageCount,
                                        long completedParticipants, long finishers) {
        TemplateStats s = new TemplateStats();
        s.templateId = templateId;
        s.usageCount = usageCount;
        s.completedParticipants = completedParticipants;
        // 표본(완료 참여자) > 10 일 때만 완주율 노출. 아니면 null(안내 문구 + 정렬 최하위).
        s.completionRate = (completedParticipants > MIN_COMPLETED_PARTICIPANTS)
                ? BigDecimal.valueOf(finishers).divide(BigDecimal.valueOf(completedParticipants), 4, java.math.RoundingMode.HALF_UP)
                : null;
        return s;
    }
}
