package com.ruleup.ruleup_backend.recommendation.service;

import com.ruleup.ruleup_backend.recommendation.domain.SegmentType;

/** 유저 1명을 이루는 세그먼트 한 축(예: COUNTRY=KR). */
public record Segment(SegmentType type, String value) {}
