package com.ruleup.ruleup_backend.common.verification;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 멤버 스크린타임 측정 대상 앱(PER_MEMBER 바인딩). challenge_members.screenApps / pendingScreenApps(JSON 배열)에 저장.
 *  - packageName : Android 패키지명(측정 키).
 *  - appName     : 바인딩 시점 앱 이름 스냅샷(앱 삭제 후에도 표시용).
 *
 * <p>challenge·verification 공유 커널. JSON 직렬화는 필드명 기반이라 패키지 이동은 저장 데이터에 영향 없음.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScreenApp(String packageName, String appName) {}
