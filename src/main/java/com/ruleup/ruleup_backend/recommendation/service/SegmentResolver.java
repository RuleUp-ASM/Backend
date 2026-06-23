package com.ruleup.ruleup_backend.recommendation.service;

import com.ruleup.ruleup_backend.recommendation.domain.SegmentType;
import com.ruleup.ruleup_backend.user.User;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;

/**
 * User → 세그먼트 축(GLOBAL·COUNTRY·GENDER·AGE_BAND·PLATFORM).
 * 인구통계/기기는 선택 입력이라 채워진 것만 내보낸다(NULL=미입력 제외).
 *  - GLOBAL  : 모든 유저 공통("ALL"). 인구통계가 전혀 없어도 '전체 인기도'로 추천되게 하는 base.
 *  - AGE_BAND: 생일 → 연령대 10년 단위 문자열("20","30"...).
 *  - PLATFORM: 가입·로그인 시 수집한 기기 플랫폼("ANDROID"/"IOS").
 */
@Component
public class SegmentResolver {

    /** GLOBAL 세그먼트의 고정 값(모든 유저 공통). */
    private static final String GLOBAL_VALUE = "ALL";

    public List<Segment> resolve(User user) {
        List<Segment> segments = new ArrayList<>();
        segments.add(new Segment(SegmentType.GLOBAL, GLOBAL_VALUE));   // 항상 포함(전체 인기도 fallback)
        if (user.getCountryCode() != null && !user.getCountryCode().isBlank()) {
            segments.add(new Segment(SegmentType.COUNTRY, user.getCountryCode()));
        }
        if (user.getGender() != null) {
            segments.add(new Segment(SegmentType.GENDER, user.getGender().name()));
        }
        String band = ageBand(user.getBirthDate());
        if (band != null) {
            segments.add(new Segment(SegmentType.AGE_BAND, band));
        }
        if (user.getPlatform() != null) {
            segments.add(new Segment(SegmentType.PLATFORM, user.getPlatform().name()));
        }
        return segments;
    }

    /** 생일 → "20"/"30" 등 10년 단위. 없거나 비정상이면 null. */
    private String ageBand(LocalDate birthDate) {
        if (birthDate == null) return null;
        int age = Period.between(birthDate, LocalDate.now()).getYears();
        if (age < 0 || age > 120) return null;
        return String.valueOf((age / 10) * 10);
    }
}
