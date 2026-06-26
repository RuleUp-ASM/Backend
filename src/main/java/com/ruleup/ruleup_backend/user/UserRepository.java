package com.ruleup.ruleup_backend.user;
import com.ruleup.ruleup_backend.user.domain.*;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

/**
 * users 테이블 접근.
 * 메서드 이름만으로 쿼리가 자동 생성된다 (Spring Data JPA).
 */
public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByOauthProviderAndOauthSubject(OAuthProvider provider, String oauthSubject);
    boolean existsByNickname(String nickname);
    Optional<User> findByIdAndDeletedAtIsNull(UUID id);
}