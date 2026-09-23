package com.ruleup.ruleup_backend.sanction;

import com.ruleup.ruleup_backend.sanction.domain.BanEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BanEntryRepository extends JpaRepository<BanEntry, UUID> {

    boolean existsByOauthHash(String oauthHash);

    boolean existsByInstallationHash(String installationHash);
}
