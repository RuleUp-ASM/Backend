package com.ruleup.ruleup_backend.score;
import com.ruleup.ruleup_backend.score.domain.*;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UserScoreSummaryRepository extends JpaRepository<UserScoreSummary, UUID> {

    /**
     * 점수 쓰기의 <b>1차 직렬화 장치</b>. 같은 사용자의 모든 점수 변경은 이 행을 잠근 뒤에 진행한다 —
     * 사이클 상태·원장·계정 상태가 서로 다른 버전으로 저장되는 것을 막는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM UserScoreSummary s WHERE s.userId = :userId")
    Optional<UserScoreSummary> findForUpdate(@Param("userId") UUID userId);
}
