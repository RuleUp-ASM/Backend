package com.ruleup.ruleup_backend.admin.access;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 관리자 서비스(role=admin) 컨텍스트 — <b>실제 배포와 같은 {@code admin} 프로필</b>.
 *
 * <p>관리자 역할 시험은 모두 이 애노테이션 하나로 같은 컨텍스트를 공유한다. 설정이 조금씩 다르면 스프링 테스트
 * 컨텍스트가 클래스마다 따로 떠서 캐시 한도를 넘기고, 밀려난 컨텍스트가 닫히며 공유 MySQL 컨테이너까지 재기동돼
 * 남은 컨텍스트가 죽은 포트를 붙잡고 멈춘다(스위트 전체에서만 나는 멈춤).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ActiveProfiles({"test", "admin"})
@SpringBootTest(properties = {
        // 시험 DB 는 스위트가 공유한다. 이 컨텍스트가 먼저 뜰 수도 있으니 스키마는 만들어 둔다.
        "spring.flyway.enabled=true",
        "app.admin.access.team-domain=" + AccessTokens.TEAM,
        "app.admin.access.audiences=" + AccessTokens.AUD,
        "app.admin.access.allowed-emails=ops@ruleup.co.kr,second@ruleup.co.kr,writer@ruleup.co.kr",
        "app.admin.access.allowed-origins=https://admin.ruleup.co.kr",
})
@Import({TestcontainersConfiguration.class, AdminRoleTest.Keys.class})
public @interface AdminRoleTest {

    /** 시험용 Access 발급자 — 관리자 역할 시험 전체가 같은 키를 쓴다. */
    AccessTokens ISSUER = new AccessTokens("kid-admin-role");

    @TestConfiguration(proxyBeanMethods = false)
    class Keys {
        @Bean
        @Primary
        AccessKeySource adminRoleTestKeySource() {
            return ISSUER::jwks;
        }
    }
}
