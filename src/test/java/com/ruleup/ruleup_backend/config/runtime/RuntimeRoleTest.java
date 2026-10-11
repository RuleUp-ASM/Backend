package com.ruleup.ruleup_backend.config.runtime;

import com.ruleup.ruleup_backend.admin.auth.AdminAuthController;
import com.ruleup.ruleup_backend.admin.controller.AdminController;
import com.ruleup.ruleup_backend.inquiry.InquiryController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static com.ruleup.ruleup_backend.config.runtime.RoleScopedHandlerMappingConfig.servedBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeRoleTest {

    @Test
    @DisplayName("공개 API 역할에는 관리자 컨트롤러가 매핑되지 않고, 관리자 역할에는 공개 컨트롤러가 매핑되지 않는다")
    void handler_split() {
        assertThat(servedBy(RuntimeRole.API, AdminController.class)).isFalse();
        assertThat(servedBy(RuntimeRole.API, AdminAuthController.class)).isFalse();
        assertThat(servedBy(RuntimeRole.API, InquiryController.class)).isTrue();

        assertThat(servedBy(RuntimeRole.ADMIN, AdminController.class)).isTrue();
        assertThat(servedBy(RuntimeRole.ADMIN, InquiryController.class)).isFalse();

        assertThat(servedBy(RuntimeRole.ALL, AdminController.class)).isTrue();
        assertThat(servedBy(RuntimeRole.ALL, InquiryController.class)).isTrue();

        // 프레임워크 핸들러(/error 등)는 어느 역할에서나 남는다
        assertThat(servedBy(RuntimeRole.ADMIN, org.springframework.boot.webmvc.autoconfigure.error.BasicErrorController.class)).isTrue();
    }

    @Test
    @DisplayName("역할을 비우면 api — 설정 누락이 관리자 경로를 여는 쪽으로 떨어지지 않는다")
    void default_is_api() {
        assertThat(new RuntimeRoleProperties(null, false).role()).isEqualTo(RuntimeRole.API);
    }

    @Test
    @DisplayName("전환 기간 플래그는 공개 API 에만 관리자 경로를 더한다 — 관리자 서비스·기본값에는 영향이 없다")
    void legacy_public_admin_flag() {
        assertThat(new RuntimeRoleProperties(RuntimeRole.API, true).effectiveRole()).isEqualTo(RuntimeRole.ALL);
        assertThat(new RuntimeRoleProperties(RuntimeRole.API, false).effectiveRole()).isEqualTo(RuntimeRole.API);
        assertThat(new RuntimeRoleProperties(RuntimeRole.ADMIN, true).effectiveRole()).isEqualTo(RuntimeRole.ADMIN);
        assertThat(new RuntimeRoleProperties(RuntimeRole.MIGRATE, true).effectiveRole()).isEqualTo(RuntimeRole.MIGRATE);
        // 배포 프로필에서도 기동은 된다(경고만) — 전환 기간에 기존 콘솔을 끊지 않기 위한 장치다
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        new RuntimeRoleGuard(new RuntimeRoleProperties(RuntimeRole.API, true), env);
        // 배경 작업은 플래그와 무관하게 공개 API 만
        assertThat(RuntimeRole.MIGRATE.runsBackgroundWork()).isFalse();
        assertThat(RuntimeRole.ADMIN.runsBackgroundWork()).isFalse();
    }

    @Test
    @DisplayName("stg·prod 프로필에서 role=all 이면 기동 실패")
    void all_is_refused_in_deployed_profiles() {
        for (String profile : new String[]{"prod", "stg"}) {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles(profile);
            assertThatThrownBy(() -> new RuntimeRoleGuard(new RuntimeRoleProperties(RuntimeRole.ALL, false), env))
                    .isInstanceOf(IllegalStateException.class);
            new RuntimeRoleGuard(new RuntimeRoleProperties(RuntimeRole.API, false), env);
            new RuntimeRoleGuard(new RuntimeRoleProperties(RuntimeRole.ADMIN, false), env);
        }
        MockEnvironment local = new MockEnvironment();
        local.setActiveProfiles("test");
        new RuntimeRoleGuard(new RuntimeRoleProperties(RuntimeRole.ALL, false), local);
    }
}
