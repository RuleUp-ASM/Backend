package com.ruleup.ruleup_backend.config.runtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * 배포 프로필에서 {@link RuntimeRole#ALL} 로 뜨는 것을 막는다.
 *
 * <p>ALL 은 일반·관리자 엔드포인트를 한 프로세스에 함께 올린다. 일반 API 는 공개 ALB 뒤에 있으므로
 * 태스크 정의 env 한 줄 실수로 관리자 API 가 다시 인터넷에 열린다. 롤백할 때도 마찬가지다 —
 * 그래서 경고가 아니라 <b>기동 실패</b>로 막는다.
 */
@Slf4j
@Component
public class RuntimeRoleGuard {

    public RuntimeRoleGuard(RuntimeRoleProperties properties, Environment environment) {
        RuntimeRole role = properties.role();
        if (role == RuntimeRole.ALL && environment.acceptsProfiles(Profiles.of("prod", "stg"))) {
            throw new IllegalStateException(
                    "배포 프로필(stg·prod)에서는 app.runtime.role=all 로 띄울 수 없다 — api 또는 admin 을 지정한다.");
        }
        if (properties.legacyPublicAdmin() && role == RuntimeRole.API) {
            log.warn("전환 기간 — 공개 API 가 관리자 경로(/api/v1/admin/**)를 계속 서빙한다. 관리자 서비스 전환이 끝나면 "
                    + "APP_RUNTIME_LEGACY_PUBLIC_ADMIN 을 지운다(infra/admin-split/README.md 4단계).");
        }
        RuntimeRole effective = properties.effectiveRole();
        log.info("실행 역할 role={} (공개 API={}, 관리자 API={}, 배경 작업={})",
                role, effective.servesPublicApi(), effective.servesAdminApi(), role.runsBackgroundWork());
    }
}
