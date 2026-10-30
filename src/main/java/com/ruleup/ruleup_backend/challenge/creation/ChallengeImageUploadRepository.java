package com.ruleup.ruleup_backend.challenge.creation;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** challenge_image_uploads 접근. */
public interface ChallengeImageUploadRepository extends JpaRepository<ChallengeImageUpload, Long> {

    /**
     * 등록 경로 전용 — 그 행을 잡고 읽는다({@code SELECT ... FOR UPDATE}).
     *
     * <p>미등록 이미지 정리도 같은 행을 잡은 채 파일을 지우므로 둘이 한 줄로 선다. 정리가 먼저면
     * 등록은 행이 사라진 뒤에 읽어 「없는 이미지」로 거절되고, 등록이 먼저면 정리는 등록된 행을
     * 보고 건너뛴다. 잠그지 않고 읽으면 정리가 파일을 지우는 사이 등록이 끼어들어 사라진 이미지를
     * 가리키는 방이 생긴다(#381).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM ChallengeImageUpload u WHERE u.imageUrl = :imageUrl")
    Optional<ChallengeImageUpload> findByImageUrlForUpdate(@Param("imageUrl") String imageUrl);
}
