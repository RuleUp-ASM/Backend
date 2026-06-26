package com.ruleup.ruleup_backend.recommendation.dto;

import com.ruleup.ruleup_backend.user.Gender;

import java.time.LocalDate;

/**
 * 윈도우 배치 집계용 투영(projection). 윈도우 내 생성된 챌린지 1건의
 * templateId + 생성자 인구통계(국가/성별/생일)만 끌어온다 → SegmentResolver로 세그먼트 환산.
 *  - User 엔티티 전체 로딩(N+1) 대신 JOIN 투영으로 필요한 컬럼만 가져온다.
 */
public record ChallengeCreatorSegmentRow(
        Long templateId,
        String countryCode,
        Gender gender,
        LocalDate birthDate
) {}
