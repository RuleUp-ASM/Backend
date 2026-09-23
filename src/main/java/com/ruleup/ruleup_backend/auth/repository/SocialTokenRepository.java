package com.ruleup.ruleup_backend.auth.repository;

import com.ruleup.ruleup_backend.auth.domain.SocialToken;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SocialTokenRepository extends JpaRepository<SocialToken, SocialToken.Key> {
}
