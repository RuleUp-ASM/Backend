package com.ruleup.ruleup_backend.sanction.domain;

import com.ruleup.ruleup_backend.common.error.ErrorCode;
import org.springframework.http.HttpMethod;

import java.util.List;

/**
 * 기능 정지의 대상 — {@link SanctionType#FEATURE_SUSPENSION} 일 때만 값이 있다.
 *
 * <p>차단 경로를 여기 함께 두는 이유는, 코드와 경로가 따로 놀면 "정지는 걸렸는데 API 는 열려 있는"
 * 상태가 조용히 생기기 때문이다. 기능을 추가할 때 한 곳만 고치면 되게 묶어 둔다.
 *
 * <p>에러 코드도 함께 갖는다. 게이트 자체는 일반적이지만 클라이언트는 <b>화면별로 분기</b>하므로,
 * 해당 API 명세가 고유 코드를 정해 둔 기능은 그 코드를 내려야 한다 — 신고는 403 {@code REPORT_SUSPENDED} 다.
 */
public enum FeatureCode {

    /** 신고 접수 — 신고 남용으로 운영자가 거는 정지. 자동 발동 경로는 없다. */
    REPORT(ErrorCode.REPORT_SUSPENDED, new Path(HttpMethod.POST, "/api/v1/reports"));

    private final ErrorCode errorCode;
    private final List<Path> blocked;

    FeatureCode(ErrorCode errorCode, Path... blocked) {
        this.errorCode = errorCode;
        this.blocked = List.of(blocked);
    }

    /** 이 기능이 정지됐을 때 내릴 코드. */
    public ErrorCode errorCode() {
        return errorCode;
    }

    /** 이 요청이 정지 대상 기능인지. */
    public boolean blocks(String method, String path) {
        return blocked.stream().anyMatch(p -> p.matches(method, path));
    }

    private record Path(HttpMethod method, String path) {
        boolean matches(String m, String p) {
            return method.name().equals(m) && path.equals(p);
        }
    }
}
