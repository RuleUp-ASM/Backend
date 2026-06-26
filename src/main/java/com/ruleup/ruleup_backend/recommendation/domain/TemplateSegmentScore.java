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
 *  - 조회 시 세그먼트 합으로 정렬해 top-N. templateId는 RoutineTemplate.id(FK 아님, 정적 카탈로그) → JOIN.
 *  - 점수는 SegmentScoreRebuildBatch가 슬라이딩 윈도우로 주기 재계산해 통째로 교체한다
 *    (실시간 증분 아님 → 캐시 무효화 thrash 없음).
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

    /** 윈도우 배치 집계 결과 1행(점수·선택 횟수를 한 번에 세팅). 전체 재계산 후 saveAll용. */
    public static TemplateSegmentScore ofAggregate(SegmentType segmentType, String segmentValue, Long templateId,
                                                   BigDecimal score, int selectionCount) {
        TemplateSegmentScore s = of(segmentType, segmentValue, templateId);
        s.score = score;
        s.selectionCount = selectionCount;
        return s;
    }
}