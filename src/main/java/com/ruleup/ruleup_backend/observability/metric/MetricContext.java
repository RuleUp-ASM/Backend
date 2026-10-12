package com.ruleup.ruleup_backend.observability.metric;

import com.ruleup.ruleup_backend.config.runtime.RuntimeRole;
import com.ruleup.ruleup_backend.config.runtime.RuntimeRoleProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 모든 운영 지표에 붙는 고정 차원 {@code Env, Service}.
 *
 * <p>Env 는 배포 프로필(prod·stg, 로컬은 local), Service 는 실행 역할로 정한다 — 같은 jar 가 공개 API 와 관리자 API 두
 * 서비스로 뜨므로, 관리자 태스크의 JVM·풀 지표가 공개 API 경보(ECS·RES)에 섞이지 않게 나눈다.
 */
@Component
public class MetricContext {

    private final String env;
    private final String service;
    private final RuntimeRole role;

    public MetricContext(@Value("${spring.profiles.active:local}") String profile, RuntimeRoleProperties runtime) {
        this.env = profile.split(",")[0].trim();
        this.role = runtime.role();
        this.service = role == RuntimeRole.ADMIN ? "ruleup-admin" : "ruleup-api";
    }

    public String env() { return env; }

    public String service() { return service; }

    public RuntimeRole role() { return role; }

    /** {@code Env, Service} 에 추가 차원을 이어 붙인다. */
    public Map<String, String> dims(String... extra) {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("Env", env);
        d.put("Service", service);
        for (int i = 0; i + 1 < extra.length; i += 2) d.put(extra[i], extra[i + 1]);
        return d;
    }
}
