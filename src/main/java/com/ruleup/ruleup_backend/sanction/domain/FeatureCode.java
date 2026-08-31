package com.ruleup.ruleup_backend.sanction.domain;

import org.springframework.http.HttpMethod;

import java.util.List;

/**
 * 기능 정지의 대상 — {@link SanctionType#FEATURE_SUSPENSION} 일 때만 값이 있다.
 *
 * <p>차단 경로를 여기 함께 두는 이유는, 코드와 경로가 따로 놀면 "정지는 걸렸는데 API 는 열려 있는"
 * 상태가 조용히 생기기 때문이다. 기능을 추가할 때 한 곳만 고치면 되게 묶어 둔다.
 */
public enum FeatureCode {

    /** 신고 접수 — 신고 남용으로 운영자가 거는 정지. 자동 발동 경로는 없다. */
    REPORT(new Path(HttpMethod.POST, "/api/v1/reports"));

    private final List<Path> blocked;

    FeatureCode(Path... blocked) {
        this.blocked = List.of(blocked);
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
