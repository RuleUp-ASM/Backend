package com.ruleup.ruleup_backend.config.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 실행 역할 설정. <b>비우면 {@link RuntimeRole#API}</b> 다 — 설정을 빠뜨렸을 때 관리자 경로가
 * 열리는 쪽이 아니라 닫히는 쪽으로 떨어져야 한다.
 *
 * <h4>전환 기간 플래그 {@code legacyPublicAdmin}</h4>
 * 관리자 API 를 별도 서비스로 옮기는 동안, 새 서비스가 준비되기 전에 기존 콘솔이 끊기면 안 된다. 그래서 공개 API
 * 태스크 정의에 {@code APP_RUNTIME_LEGACY_PUBLIC_ADMIN=true} 를 <b>먼저</b> 넣고 이 코드를 배포하면, 공개 API 는
 * 예전처럼 관리자 경로(비밀번호 진입 + 앱 JWT)를 계속 서빙한다. 새 서비스 검증이 끝나면 플래그를 지운다
 * (infra/admin-split/README.md 4단계). 켜져 있는 동안은 기동 때마다 경고를 남긴다.
 * 공개 API 역할에서만 의미가 있다 — 관리자·마이그레이션 역할에서는 무시된다.
 *
 * @param role              api · admin · migrate · all(로컬·시험 전용)
 * @param legacyPublicAdmin 전환 기간에만 — 공개 API 가 관리자 경로를 계속 서빙한다
 */
@ConfigurationProperties(prefix = "app.runtime")
public record RuntimeRoleProperties(RuntimeRole role, boolean legacyPublicAdmin) {

    public RuntimeRoleProperties {
        if (role == null) role = RuntimeRole.API;
    }

    /** 엔드포인트 등록에 쓰는 역할. 전환 기간의 공개 API 는 관리자 경로까지 함께 올린다. */
    public RuntimeRole effectiveRole() {
        return role == RuntimeRole.API && legacyPublicAdmin ? RuntimeRole.ALL : role;
    }
}
