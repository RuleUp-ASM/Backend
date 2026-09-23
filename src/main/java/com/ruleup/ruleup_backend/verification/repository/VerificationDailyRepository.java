package com.ruleup.ruleup_backend.verification.repository;

import com.ruleup.ruleup_backend.verification.domain.VerificationDaily;
import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** verification_daily 접근. 멤버×날짜 1줄 + 확정 배치 폴링. */
public interface VerificationDailyRepository extends JpaRepository<VerificationDaily, UUID> {

    /** 그 멤버의 그 날 인증 행(없으면 생성). */
    Optional<VerificationDaily> findByChallengeMemberIdAndTargetDate(UUID challengeMemberId, LocalDate targetDate);

    /** 진행률 재계산용 상태별 카운트. */
    long countByChallengeMemberIdAndStatus(UUID challengeMemberId, VerificationStatus status);

    /** 사이클 한 구간의 상태별 카운트 — 방 홈의 "이번 주 N/M"(myWeekly.done). */
    long countByChallengeMemberIdAndStatusAndTargetDateBetween(
            UUID challengeMemberId, VerificationStatus status, LocalDate from, LocalDate to);

    /** 챌린지 내 특정 상태(예: SUCCESS) 인증 이력 존재 여부 — 진행 중 삭제 패널티 트리거 판정(§8). */
    boolean existsByChallengeIdAndStatus(UUID challengeId, VerificationStatus status);

    /**
     * 그 멤버의 가장 이른 성공일. 중도 탈퇴 감점의 "1년 이상 성공을 이어왔는가"(정책 §10.1) 판정에 쓴다.
     * 성공 이력이 없으면 null.
     */
    @Query("SELECT MIN(v.targetDate) FROM VerificationDaily v "
            + "WHERE v.challengeMemberId = :memberId AND v.status = :status")
    LocalDate findEarliestDate(@Param("memberId") UUID memberId, @Param("status") VerificationStatus status);

    /** 상세 화면 최근 로그(§3.3 dailyLogs). */
    List<VerificationDaily> findByChallengeMemberIdOrderByTargetDateDesc(UUID challengeMemberId);

    /** 캘린더 당일 보강: 유저의 특정 날짜 인증 행(RoutineOutcome 지연분 보완). */
    List<VerificationDaily> findByUserIdAndTargetDate(UUID userId, LocalDate targetDate);

    /**
     * 여러 유저의 같은 날짜 행 — 루틴 리마인더가 <b>한 번에</b> 읽는다.
     * 건당 조회하면 6만 멤버십에서 N+1 이 난다.
     */
    List<VerificationDaily> findByUserIdInAndTargetDate(List<UUID> userIds, LocalDate targetDate);

    /** 점수 정산: 한 사이클(7일 구간) 안의 내 판정 전부. 확정 여부는 읽는 쪽이 가린다. */
    List<VerificationDaily> findByUserIdAndChallengeIdAndTargetDateBetween(
            UUID userId, UUID challengeId, LocalDate from, LocalDate to);

    List<VerificationDaily> findByChallengeIdAndStatusIn(
            UUID challengeId, Collection<VerificationStatus> statuses);

    /** 방 스레드 첫 페이지. 공유 가능한 종결 이벤트만 DB에서 정렬하고 size+1 건만 읽는다. */
    @Query(value = "SELECT * FROM VerificationDaily WHERE challengeId=:challengeId AND " +
            "((status='SUCCESS' AND verifiedAt IS NOT NULL AND verifiedAt<=:now) OR " +
            " (status='FAILED' AND shareableAt IS NOT NULL AND shareableAt<=:now)) " +
            "ORDER BY (CASE WHEN status='SUCCESS' THEN verifiedAt ELSE shareableAt END) DESC, id DESC",
            nativeQuery = true)
    List<VerificationDaily> findThreadFirstPage(@Param("challengeId") UUID challengeId,
                                                 @Param("now") Instant now,
                                                 Pageable pageable);

    /** 방 스레드 다음 페이지. (노출 시각,id) seek 커서로 누락·중복 없는 페이지를 읽는다. */
    @Query(value = "SELECT * FROM VerificationDaily WHERE challengeId=:challengeId AND " +
            "((status='SUCCESS' AND verifiedAt IS NOT NULL AND verifiedAt<=:now) OR " +
            " (status='FAILED' AND shareableAt IS NOT NULL AND shareableAt<=:now)) AND " +
            "((CASE WHEN status='SUCCESS' THEN verifiedAt ELSE shareableAt END)<:cursorAt OR " +
            " ((CASE WHEN status='SUCCESS' THEN verifiedAt ELSE shareableAt END)=:cursorAt AND id<:cursorId)) " +
            "ORDER BY (CASE WHEN status='SUCCESS' THEN verifiedAt ELSE shareableAt END) DESC, id DESC",
            nativeQuery = true)
    List<VerificationDaily> findThreadNextPage(@Param("challengeId") UUID challengeId,
                                                @Param("now") Instant now,
                                                @Param("cursorAt") Instant cursorAt,
                                                @Param("cursorId") UUID cursorId,
                                                Pageable pageable);

    /** 한 페이지 작성자들의 종결 이력. 각 이벤트 시점의 스트릭을 계산할 때 사용한다. */
    List<VerificationDaily> findByChallengeIdAndUserIdInAndStatusInOrderByUserIdAscTargetDateAsc(
            UUID challengeId, Collection<UUID> userIds, Collection<VerificationStatus> statuses);

    /** 확정 배치: 유예까지 끝나 이제 잠가도 되는 PENDING 행(§2.14). */
    List<VerificationDaily> findByStatusAndFinalizeAfterLessThanEqual(VerificationStatus status, Instant now);

    /**
     * 확정 배치 클레임: 확정 시각이 지난 미확정 행을 FOR UPDATE SKIP LOCKED 로 선점.
     * 동시에 도는 스케줄러는 잠긴 행을 건너뛰어 중복 확정이 구조적으로 불가능하다(ShedLock 없이 멱등).
     *
     * <p>조건이 <b>둘</b>인 이유가 있다. 행에 적힌 {@code finalizeAfter} 만 보면, 그 값이 어떤 경로로든
     * 앞당겨져 저장된 행을 계속 집어 온다. 확정 쪽은 귀속일에서 다시 파생한 시각으로 한 번 더 걸러
     * 거절하는데(「D+2 이전 확정 0건」), 거절은 <b>행을 바꾸지 않으므로</b> 같은 행이 다음 조회에
     * 또 걸린다 — 폴러가 45초 예산을 그 한 건에 다 쓰고 뒤에 밀린 정상 대상이 굶는다.
     * 그래서 귀속일 자체에도 상한을 걸어 <b>애초에 집지 않는다.</b>
     *
     * @param maxTargetDate 확정 경계가 지난 가장 늦은 귀속일 = KST 오늘 − 2일
     *                      (귀속일 D 의 확정 경계가 D+2 00:00 KST 이므로 D ≤ 오늘−2 여야 지난 것이다)
     */
    @Query(value = "SELECT * FROM VerificationDaily " +
            "WHERE status = 'PENDING' AND finalizeAfter IS NOT NULL AND finalizeAfter <= :now " +
            "  AND (finalizeRetryAt IS NULL OR finalizeRetryAt <= :now) " +
            "  AND targetDate <= :maxTargetDate " +
            "ORDER BY finalizeAfter LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<VerificationDaily> findDuePendingForUpdate(@Param("now") Instant now,
                                                    @Param("maxTargetDate") LocalDate maxTargetDate,
                                                    @Param("limit") int limit);


    /**
     * 확정에 실패한 한 건을 뒤로 미룬다.
     *
     * <p>정책 기한 {@code finalizeAfter} 는 유지하고 {@code finalizeRetryAt} 만 변경한다.
     * 실패한 행을 그대로 두면 폴러가 매번
     * 같은 행을 먼저 집어 <b>뒤에 쌓인 정상 건이 통째로 굶는다</b>. 잠깐 미뤄 두면 나머지가 흐르고,
     * 그 사이 원인이 해소되면 다음 차례에 스스로 확정된다.
     */
    /**
     * 결과 모달 확인 시각만 기록한다 — 판정 필드를 건드리지 않는 표시 상태라 엔티티 저장 가드
     * ({@code VerificationDaily#validateIntegrity}) 를 거치지 않는다. 가드를 타면 복구 전의 이상 행에서
     * 확인이 500 이 되어 모달이 영영 닫히지 않는다. 멱등: 이미 확인했으면 첫 시각을 유지한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE VerificationDaily d SET d.acknowledgedAt = :at, d.version = d.version + 1 "
            + "WHERE d.id = :id AND d.acknowledgedAt IS NULL")
    int acknowledge(@Param("id") UUID id, @Param("at") Instant at);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE VerificationDaily d SET d.finalizeRetryAt = :next, d.version = d.version + 1 "
            + "WHERE d.id = :id AND d.status = com.ruleup.ruleup_backend.common.verification.VerificationStatus.PENDING")
    int deferFinalize(@Param("id") UUID id, @Param("next") Instant next);

    /**
     * 추천 아웃컴 수집(RoutineOutcomeCollector): 확정 시각이 워터마크 이후인 종결(SUCCESS/FAILED) 행.
     * verifiedAt 오름차순 → 페이지 상한(Pageable)으로 한 배치 처리량을 제한한다.
     */
    @Query("SELECT d FROM VerificationDaily d " +
            "WHERE d.status IN :statuses AND d.verifiedAt IS NOT NULL AND d.verifiedAt >= :since " +
            "ORDER BY d.verifiedAt ASC")
    List<VerificationDaily> findTerminalSince(@Param("statuses") Collection<VerificationStatus> statuses,
                                              @Param("since") Instant since, Pageable pageable);
    @Query("""
            select d from VerificationDaily d where d.status = com.ruleup.ruleup_backend.common.verification.VerificationStatus.FAILED
             and d.verifiedAt >= :since and d.verifiedAt <= :now and d.shareableAt <= :now
             and (:cursor is null or d.id > :cursor) order by d.id
            """)
    List<VerificationDaily> findWatcherRecoveryPage(@Param("since") Instant since, @Param("now") Instant now,
                                                    @Param("cursor") UUID cursor, Pageable pageable);

    /**
     * 확정 전인 특정 귀속일의 건을 id 순으로 한 쪽씩 — 실패 예정 알림 배치용.
     * {@code finalizeAfter} 가 귀속일로 정해지므로 (status, finalizeAfter) 인덱스를 탄다.
     */
    @Query(value = "SELECT * FROM VerificationDaily WHERE status = 'PENDING' AND finalizeAfter = :finalizeAfter "
            + "AND id > :after ORDER BY id LIMIT :limit", nativeQuery = true)
    List<VerificationDaily> findPendingByFinalizeAfterPage(@Param("finalizeAfter") Instant finalizeAfter,
                                                          @Param("after") UUID after, @Param("limit") int limit);
}
