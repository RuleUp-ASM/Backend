package com.ruleup.ruleup_backend.recommendation.service;

import com.ruleup.ruleup_backend.recommendation.domain.SegmentType;

import java.util.EnumMap;
import java.util.Map;

/**
 * 특성 가중치 w(type)의 prior(기본값)와 학습 안전장치 상수(추천 스펙 §6·§8.1).
 *
 * <p>가중치의 <i>값</i>은 배치가 실제 선택 데이터로 학습하지만(§8.1), 데이터가 얇을 땐 여기 prior로
 * 끌어당긴다(shrinkage). 학습 이전(테이블 비었을 때)의 조회 폴백도 이 prior다.
 *
 * <ul>
 *   <li>GLOBAL 0.3(고정, 학습 제외) — 전체 인기가 개인 특성을 덮지 않게 눌러둔다.</li>
 *   <li>AGE_BAND 1.2 — 연령대가 취향을 가장 잘 가른다는 사전지식(약간 우대).</li>
 *   <li>PLATFORM 0.5 — 기기(안드/iOS)는 취향 신호가 약함.</li>
 *   <li>COUNTRY·GENDER 1.0 — 중립.</li>
 * </ul>
 */
public final class SegmentWeightPolicy {

    /** GLOBAL 고정 가중치(자기 자신과의 비교라 발산 0 → 학습하지 않음). */
    public static final double GLOBAL_FIXED = 0.3;
    /** shrinkage 강도 K: α = n / (n + K). 표본이 K 수준까지는 prior 우세. */
    public static final int SHRINKAGE_K = 500;
    /** 최종 가중치 상·하한(한 축이 폭주하지 못하게). */
    public static final double CLAMP_MIN = 0.2;
    public static final double CLAMP_MAX = 2.0;
    /** 구별력 D(T)를 축들 사이에서 정규화해 매핑할 데이터 가중치 범위. */
    public static final double WDATA_MIN = 0.5;
    public static final double WDATA_MAX = 1.5;

    private static final Map<SegmentType, Double> PRIOR = new EnumMap<>(SegmentType.class);
    static {
        PRIOR.put(SegmentType.GLOBAL, GLOBAL_FIXED);
        PRIOR.put(SegmentType.COUNTRY, 1.0);
        PRIOR.put(SegmentType.GENDER, 1.0);
        PRIOR.put(SegmentType.AGE_BAND, 1.2);
        PRIOR.put(SegmentType.PLATFORM, 0.5);
    }

    private SegmentWeightPolicy() {}

    /** 축 T의 prior. 미지정 축은 1.0으로 폴백(설정 누락이 추천을 깨지 않게). */
    public static double prior(SegmentType type) {
        return PRIOR.getOrDefault(type, 1.0);
    }

    /** 최종 가중치를 [CLAMP_MIN, CLAMP_MAX]로 제한. */
    public static double clamp(double w) {
        return Math.max(CLAMP_MIN, Math.min(CLAMP_MAX, w));
    }
}
