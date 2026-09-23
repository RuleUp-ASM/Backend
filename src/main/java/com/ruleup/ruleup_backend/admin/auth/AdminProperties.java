package com.ruleup.ruleup_backend.admin.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 운영자 콘솔 진입 설정 — 백오피스 공통 5-2-1 B.
 *
 * <p><b>비밀번호는 서버 env 에만 둔다.</b> 프론트 env 에 넣으면 번들에 실려 누구나 읽을 수 있고,
 * 그러면 접근 통제가 성립하지 않는다. 값이 비어 있으면 로그인 자체가 불가능하다 —
 * <b>미설정이 곧 비활성</b>이며, 빈 비밀번호로 열리는 문을 만들지 않는다.
 *
 * <p>{@code operatorId} 는 이 비밀번호로 들어온 세션이 <b>누구로 기록될지</b>다. 감사 로그는
 * 조작자를 남겨야 하는데 비밀번호 하나에는 신원이 없으므로, 운영자 롤을 가진 실제 계정을
 * 지정해 그 계정으로 기록한다. 운영자가 둘 이상 생기면 이 방식으로는 서로를 구분할 수 없고,
 * 그때가 공통 오픈 이슈 #2(운영자 계정 인증 방식)를 닫아야 하는 시점이다.
 *
 * <p><b>비워 두면 서버가 운영자 롤 계정을 찾아 쓴다.</b> 배포마다 UUID 를 넣게 하면 롤을 옮길
 * 때 설정과 DB 가 어긋나고, 어긋난 순간 로그인만 조용히 막힌다 — 롤 부여가 이미 운영 결정이므로
 * 그 결정 하나로 충분하다.
 *
 * @param passcode   진입 비밀번호. 비어 있으면 로그인 비활성.
 * @param operatorId 발급 토큰의 주체가 될 운영자 계정 id(UUID 문자열). 비우면 자동 탐색.
 */
@ConfigurationProperties(prefix = "app.admin")
public record AdminProperties(String passcode, String operatorId) {

    /** 비밀번호가 있어야 문이 열린다. <b>미설정이 곧 비활성</b>이다. */
    public boolean isConfigured() {
        return passcode != null && !passcode.isBlank();
    }

    public boolean hasOperatorId() {
        return operatorId != null && !operatorId.isBlank();
    }
}
