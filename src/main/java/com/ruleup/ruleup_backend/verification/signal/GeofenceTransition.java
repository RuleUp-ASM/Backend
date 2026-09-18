package com.ruleup.ruleup_backend.verification.signal;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/**
 * 지오펜스 전환(테크스펙 v2 §6.2). transition = ENTER|EXIT|DWELL.
 *  - isMock: 위치 신호 필수(§6.3). null(누락)이면 판정 근거에서 뺀다.
 *
 * <h4>Android 와이어를 그대로 받는다</h4>
 * 앱의 전송 스펙은 지오펜스 id 를 {@code anchorId}, 시각을 {@code observedAt}(epoch millis)로 보낸다.
 * 서버가 {@code geofenceId}·ISO {@code at} 만 알아들을 때는 두 값이 null 로 저장돼 실기기 GPS 가
 * 한 번도 판정에 들어가지 못했다(QA SIG-19) — 방문형은 체류 0분으로 실패, 회피형은 진입을 못 봐
 * 거짓 통과였다. 별칭으로 받고, 숫자 시각은 {@code TimeWindows.parseInstant} 가 해석한다.
 *
 * <h4>id 는 두 형식이 공존한다</h4>
 * 서버 계약은 {@code geofenceId = challengeMemberId} 이고, 앱은 등록 때 붙인 requestId
 * {@code "{userId}#{challengeId}#{index}"} 를 보낸다. {@link #belongsTo} 가 둘 다 이 멤버의 전환으로 본다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GeofenceTransition(
        @JsonAlias({"requestId", "anchorId"}) String geofenceId,
        String transition,
        @JsonAlias("observedAt") String at,
        Boolean isMock) {

    private static final char ANCHOR_SEPARATOR = '#';

    /**
     * 이 전환이 그 멤버의 지오펜스에서 났는가.
     *
     * <p>앱 형식은 사용자와 챌린지를 <b>둘 다</b> 맞춰 본다. 챌린지만 보면 같은 기기에서 계정을 바꿨을 때
     * 남아 있던 예전 계정의 지오펜스가 같은 방의 다른 멤버를 인증해 줄 수 있다.
     */
    public boolean belongsTo(String memberId, UUID userId, UUID challengeId) {
        if (geofenceId == null) return false;
        if (geofenceId.equals(memberId)) return true;
        if (userId == null || challengeId == null) return false;
        String prefix = userId.toString() + ANCHOR_SEPARATOR + challengeId + ANCHOR_SEPARATOR;
        return geofenceId.regionMatches(true, 0, prefix, 0, prefix.length())
                && geofenceId.length() > prefix.length();
    }
}
