package com.ruleup.ruleup_backend.sanction;

import com.ruleup.ruleup_backend.sanction.domain.Sanction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SanctionRepository extends JpaRepository<Sanction, UUID> {

    /**
     * 계정 게이트의 주 조회 — 현재 유효한 제재.
     *
     * <p>조건이 세 갈래인 이유는 {@code Sanction#isActiveAt} 주석과 같다. 동결분
     * ({@code frozen_remaining_sec} 있음)과 영구 정지({@code ends_at} null)를 빠뜨리면
     * 제재가 조용히 풀린다.
     */
    @Query("""
            select s from Sanction s
             where s.userId = :userId
               and s.revokedAt is null
               and (s.frozenRemainingSec is not null
                    or s.endsAt is null
                    or s.endsAt > :now)
             order by s.startsAt desc
            """)
    List<Sanction> findActive(@Param("userId") UUID userId, @Param("now") Instant now);

    /** 마이페이지 제재 이력 — 트랙별로 나눠 내려야 하므로 track 이 선두에 들어간다. */
    List<Sanction> findByUserIdAndTrackOrderByStartsAtDesc(UUID userId,
            com.ruleup.ruleup_backend.sanction.domain.SanctionTrack track);

    List<Sanction> findByUserIdOrderByStartsAtDesc(UUID userId);

    /**
     * 기간이 지난 제재만.
     *
     * <p>⚠️ 조건을 {@code ends_at is null or ends_at <= :now} 로 넓히면 <b>동결분과 영구 정지가
     * 통째로 풀린다</b>. {@code ends_at} 이 실제로 있고 지난 건만 고른다.
     */
    @Query("""
            select s from Sanction s
             where s.revokedAt is null
               and s.frozenRemainingSec is null
               and s.endsAt is not null
               and s.endsAt <= :now
            """)
    List<Sanction> findExpired(@Param("now") Instant now);

    /** 탈퇴 동결 대상 — 진행 중인 제재만. */
    @Query("""
            select s from Sanction s
             where s.userId = :userId
               and s.revokedAt is null
               and s.endsAt is not null
               and s.endsAt > :now
            """)
    List<Sanction> findFreezable(@Param("userId") UUID userId, @Param("now") Instant now);

    /** 복원 해동 대상. */
    List<Sanction> findByUserIdAndFrozenRemainingSecIsNotNull(UUID userId);

    /** 고지 없이 집행된 직권 제재 감사 — 상시 0에 가깝게 유지돼야 한다. */
    long countByNotifiedAtIsNullAndTrack(com.ruleup.ruleup_backend.sanction.domain.SanctionTrack track);
}
