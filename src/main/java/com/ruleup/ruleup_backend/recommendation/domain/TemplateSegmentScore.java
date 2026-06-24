package com.ruleup.ruleup_backend.recommendation.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 세그먼트별 템플릿 인기 점수 (TemplateSegmentScore 테이블) — 추천 warm-up.
 * 복합 PK (segmentType, segmentValue, templateId). 인증/챌린지와 달리 단일 UUID PK가 아님.
 *  - 점수는 SegmentScoreBatch 가 주기적으로 누적 챌린지를 재집계해 통째로 재작성한다(실시간 X).
 *  - 조회 시 유저 세그먼트들의 점수 합으로 정렬해 top-N.
 *  - templateId는 RoutineTemplate.id를 가리키지만 FK 아님(정적 카탈로그, 앱 검증) → JOIN으로 사용.
 */
@Entity
@Table(name = "TemplateSegmentScore")
@IdClass(TemplateSegmentScoreId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TemplateSegmentScore {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "segmentType", nullable = false)
    private SegmentType segmentType;

    @Id
    @Column(name = "segmentValue", nullable = false, length = 20)
    private String segmentValue;

    @Id
    @Column(name = "templateId", nullable = false)
    private Long templateId;

    @Column(name = "score", nullable = false, precision = 12, scale = 4)
    private BigDecimal score = BigDecimal.ZERO;

    @Column(name = "selectionCount", nullable = false)
    private int selectionCount = 0;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    public static TemplateSegmentScore of(SegmentType segmentType, String segmentValue, Long templateId) {
        TemplateSegmentScore s = new TemplateSegmentScore();
        s.segmentType = segmentType;
        s.segmentValue = segmentValue;
        s.templateId = templateId;
        return s;
    }

    /** 배치 재집계용: 누적 선택 수(count)로 점수를 통째로 설정(선택 1건 = 가중 1.0). */
    public static TemplateSegmentScore rebuilt(SegmentType segmentType, String segmentValue,
                                               Long templateId, long count) {
        TemplateSegmentScore s = of(segmentType, segmentValue, templateId);
        s.score = BigDecimal.valueOf(count);
        s.selectionCount = (int) count;
        return s;
    }
}