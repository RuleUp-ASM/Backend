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
 * 세그먼트 축(type)별 학습 가중치 (SegmentTypeWeight 테이블) — 추천 §8.1.
 *
 * <p>축마다 1행(총 5행)짜리 초경량 테이블. "어느 특성이 취향을 잘 가르는지"를 배치가 실제 선택
 * 데이터로 재계산해(JSD 기반 구별력 + shrinkage + clamp) 점수와 <b>같은 트랜잭션</b>에서 재작성한다.
 * 조회는 {@link com.ruleup.ruleup_backend.recommendation.service.SegmentTypeWeightReader} 로 읽어
 * {@code w(type) × 저장점수} 로 곱한다. GLOBAL 은 학습하지 않고 0.3 고정.
 *
 * <ul>
 *   <li>weight     : 이번 배치가 학습한 가중치(clamp [0.2, 2.0]).</li>
 *   <li>sampleSize : 학습에 쓰인 그 축의 총 선택 수(shrinkage 표본, 디버깅용).</li>
 * </ul>
 */
@Entity
@Table(name = "SegmentTypeWeight")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SegmentTypeWeight {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "segmentType", nullable = false)
    private SegmentType segmentType;

    @Column(name = "weight", nullable = false, precision = 6, scale = 4)
    private BigDecimal weight;

    @Column(name = "sampleSize", nullable = false)
    private long sampleSize;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    /** 배치 학습 결과 1행. weight 는 이미 clamp 된 최종값. */
    public static SegmentTypeWeight learned(SegmentType type, double weight, long sampleSize) {
        SegmentTypeWeight w = new SegmentTypeWeight();
        w.segmentType = type;
        w.weight = BigDecimal.valueOf(weight);
        w.sampleSize = sampleSize;
        return w;
    }
}
