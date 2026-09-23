package com.ruleup.ruleup_backend.user.domain;

/**
 * 성별 (user_information.gender).
 *
 * <p><b>가입으로 들어올 수 있는 값은 MALE/FEMALE 뿐이다</b>(AuthService#parseGender). 아래 둘은
 * 「미응답」을 어떻게 적을지 합의되기 전에 양쪽 표기가 함께 들어온 흔적으로, 지금 정책은 성별을
 * 건너뛸 수 없게 하므로 새로 쌓이지 않는다. 남겨 두는 것은 이미 저장된 값을 읽기 위해서다.
 */
public enum Gender {
    MALE, FEMALE, NON_BINARY, PREFER_NOT_TO_SAY
}
