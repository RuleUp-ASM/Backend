package com.ruleup.ruleup_backend.routine.domain;

/**
 * 자동 인증에 웨어러블(워치)이 필요한 정도.
 *  - NONE     : 폰만으로 가능
 *  - OPTIONAL : 있으면 정확도↑, 없어도 폰으로 가능
 *  - REQUIRED : 워치 없으면 자동 인증 불가(예: 마음챙김 세션 기록)
 *
 * 서버는 사용자의 워치 보유 여부를 알 수 없으므로, 이 값은 응답으로 내려보내
 * 클라이언트가 안내/판단하도록 한다(자동 가용 판정은 권한만으로 한다).
 */
public enum WearableRequirement {
    NONE, OPTIONAL, REQUIRED
}