package com.ruleup.ruleup_backend.recommendation.service;

import com.ruleup.ruleup_backend.recommendation.domain.SegmentType;
import com.ruleup.ruleup_backend.user.Gender;
import com.ruleup.ruleup_backend.user.User;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;

/**
 * User → 세그먼트 축(COUNTRY·GENDER·AGE_BAND). 인구통계는 선택 입력이라 채워진 것만(NULL=미입력 제외).
 *  - AGE_BAND: 생일 → 연령대 10년 단위 문자열("20","30"...).
 */
@Component
public class SegmentResolver {

    public List<Segment> resolve(User user) {
        return resolve(user.getCountryCode(), user.getGender(), user.getBirthDate());
    }

    /** 원시 인구통계(국가/성별/생일)에서 세그먼트 산출. 배치 투영(ChallengeCreatorSegmentRow)에서 직접 호출. */
    public List<Segment> resolve(String countryCode, Gender gender, LocalDate birthDate) {
        List<Segment> segments = new ArrayList<>();
        if (countryCode != null && !countryCode.isBlank()) {
            segments.add(new Segment(SegmentType.COUNTRY, countryCode));
        }
        if (gender != null) {
            segments.add(new Segment(SegmentType.GENDER, gender.name()));
        }
        String band = ageBand(birthDate);
        if (band != null) {
            segments.add(new Segment(SegmentType.AGE_BAND, band));
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
