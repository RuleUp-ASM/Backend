package com.ruleup.ruleup_backend.challenge.creation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** challenge_image_uploads 접근. */
public interface ChallengeImageUploadRepository extends JpaRepository<ChallengeImageUpload, Long> {

    Optional<ChallengeImageUpload> findByImageUrl(String imageUrl);
}
