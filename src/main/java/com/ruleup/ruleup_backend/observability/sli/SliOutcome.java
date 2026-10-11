package com.ruleup.ruleup_backend.observability.sli;

import com.ruleup.ruleup_backend.common.error.ErrorCode;

import java.util.Set;

/**
 * 요청 한 건이 SLI 에서 무엇인가 — 노션 「알람 카탈로그」 §1 「SLI 분자·분모」.
 *
 * <ul>
 *   <li>{@link #EXCLUDED} 적격 요청이 아니다 — 시험·합성 점검, 그리고 <b>사전에 정한 정상 정책 거절</b>(만료 토큰·권한·
 *       정원 초과·정책 rate limit 처럼 {@link ErrorCode} 로 이름 붙은 4xx)</li>
 *   <li>{@link #BAD} 가용성 실패 — 5xx, 이름 없는 429(과부하), 계약 회귀 4xx({@link #CONTRACT_REGRESSION}),
 *       그리고 {@link ErrorCode} 없이 나간 4xx(분류되지 않은 거절은 정상으로 치지 않는다)</li>
 *   <li>{@link #GOOD} 그 밖의 응답. 인증 결과가 FAILED·NOT_TARGET 이거나 sync 중복 제거가 있어도 응답이 정상이면 GOOD 이다</li>
 * </ul>
 */
public enum SliOutcome {
    GOOD, BAD, EXCLUDED;

    /**
     * 지원하는 앱이 보내면 안 되는 형식의 요청 — 앱·서버 계약이 어긋났다는 신호라 실패로 센다.
     * sync 명세의 「필수 필드 누락으로 400 반복」 사례가 이 모양이었다.
     */
    static final Set<ErrorCode> CONTRACT_REGRESSION = Set.of(ErrorCode.INVALID_REQUEST);

    public static SliOutcome classify(int status, ErrorCode code, boolean synthetic) {
        if (synthetic) return EXCLUDED;
        if (status >= 500) return BAD;
        if (status < 400) return GOOD;
        if (code == null) return BAD;                       // 이름 없는 4xx·429 — 미분류 거절·과부하
        if (CONTRACT_REGRESSION.contains(code)) return BAD;
        return EXCLUDED;                                     // 이름 붙은 정책 거절(정책 rate limit 포함)
    }
}
