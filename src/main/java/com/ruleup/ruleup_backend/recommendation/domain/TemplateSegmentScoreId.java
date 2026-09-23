package com.ruleup.ruleup_backend.recommendation.domain;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * TemplateSegmentScore 복합 PK (segmentType, segmentValue, templateId).
 * @IdClass 용 — 필드명/타입이 엔티티의 @Id 필드와 일치해야 함.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class TemplateSegmentScoreId implements Serializable {
    private SegmentType segmentType;
    private String segmentValue;
    private Long templateId;
}