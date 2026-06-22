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
 *  - 선택(챌린지 생성) 시 해당 세그먼트 score↑, 조회 시 세그먼트 합으로 정렬해 top-N.
 *  - templateId는 RoutineTemplate.id를 가리키지만 FK 아님(정적 카탈로그, 앱 검증) → JOIN으로 사용.
 *  - 실제 증가는 보통 native upsert(ON DUPLICATE KEY)로 처리. 이 엔티티는 조회/단건 갱신용.
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

    /** 선택 1건 반영(가중 점수 누적). native upsert를 안 쓰고 JPA로 갱신할 때 사용. */
    public void addScore(BigDecimal delta) {
        this.score = this.score.add(delta);
        this.selectionCount++;
    }
}