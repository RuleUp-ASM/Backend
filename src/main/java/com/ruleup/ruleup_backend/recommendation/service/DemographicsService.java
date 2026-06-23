package com.ruleup.ruleup_backend.recommendation.service;

import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.recommendation.dto.DemographicsRequest;
import com.ruleup.ruleup_backend.user.Gender;
import com.ruleup.ruleup_backend.user.User;
import com.ruleup.ruleup_backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * 추천용 인구통계 수집(가입 후 최초 접속, 선택). 채워진 값만 저장하고, 안 보낸 필드는 건너뛴다.
 * 형식이 잘못된 값(비-ISO 날짜, 알 수 없는 성별)만 400으로 막는다.
 * 국가 코드는 여기서 받지 않는다 — 서버가 가입·로그인 시 요청에서 해석해 채운다(CountryResolver).
 */
@Service
@RequiredArgsConstructor
public class DemographicsService {

    private final UserRepository userRepository;

    @Transactional
    public void update(UUID userId, DemographicsRequest req) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.LOGIN_REQUIRED));
        user.registerDemographics(parseBirth(req.birthDate()), parseGender(req.gender()));
    }

    /** ISO 날짜(yyyy-MM-dd). 비거나 없으면 null(건너뜀), 형식/미래 등 비정상이면 400. */
    private LocalDate parseBirth(String birthDate) {
        if (birthDate == null || birthDate.isBlank()) return null;
        try {
            LocalDate d = LocalDate.parse(birthDate.trim());
            if (d.isAfter(LocalDate.now())) throw new BusinessException(ErrorCode.INVALID_REQUEST);
            return d;
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }

    /** "MALE"/"FEMALE". 비거나 없으면 null(건너뜀), 알 수 없으면 400. */
    private Gender parseGender(String gender) {
        if (gender == null || gender.isBlank()) return null;
        try {
            return Gender.valueOf(gender.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }
}
