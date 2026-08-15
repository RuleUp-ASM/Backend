package com.ruleup.ruleup_backend.user;
import com.ruleup.ruleup_backend.user.domain.*;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * users 테이블 접근.
 * 닉네임 점유 판정은 문서(DB 정리 §7.1) 규칙을 따른다:
 * 탈퇴하지 않은 타인의 "PENDING 신청 닉네임" 또는 "승인 닉네임"과 겹치면 사용 불가.
 * (REJECTED/CONFLICT 신청값·탈퇴자 닉네임은 점유하지 않음)
 */
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByOauthProviderAndOauthSubject(OAuthProvider provider, String oauthSubject);

    Optional<User> findByIdAndDeletedAtIsNull(UUID id);

    /**
     * 계정 상태만 읽는다 — 요청마다 제재를 확인하는 {@code AccountStatusFilter} 전용.
     * 엔티티 전체(관심사·개인정보 컬렉션 포함)를 로드하지 않으려고 projection 으로 둔다.
     */
    @Query("select u.status from User u where u.id = :id")
    Optional<UserStatus> findStatusById(@Param("id") UUID id);

    /** 닉네임 점유 여부 — excludeId(본인) 제외. 신규 가입 검사면 excludeId=null. */
    @Query("""
            select count(u) > 0 from User u
            where (:excludeId is null or u.id <> :excludeId)
              and u.status <> com.ruleup.ruleup_backend.user.domain.UserStatus.WITHDRAWN
              and ((u.nicknameStatus = com.ruleup.ruleup_backend.user.domain.NicknameStatus.PENDING
                        and u.nickname = :nickname)
                   or u.approvedNickname = :nickname)
            """)
    boolean isNicknameTaken(@Param("nickname") String nickname, @Param("excludeId") UUID excludeId);

    /**
     * 현재 이 설치를 점유 중인 <b>활성</b> 사용자 — 기존 회원 로그인 시 설치 인계 처리용.
     * 탈퇴 행도 installation_id 를 유지하므로(재가입 승계 근거) 반드시 활성만 걸러야 한다.
     * 활성은 uq_users_active_installation_id 로 최대 1건이 보장된다.
     */
    @Query("""
            select u from User u
            where u.installationId = :installationId
              and u.status <> com.ruleup.ruleup_backend.user.domain.UserStatus.WITHDRAWN
            """)
    Optional<User> findActiveHolderOfInstallation(@Param("installationId") String installationId);


    /**
     * 이 설치를 쥔 채 탈퇴한 계정 — 다른 소셜 계정의 신규 가입을 막는 근거(회원 정책 §1).
     *
     * <p>탈퇴해도 installation_id 를 비우지 않기 때문에 남아 있다. 소셜만 바꿔 같은 설치에서
     * 새로 시작하는 것이 곧 점수·제재 리셋이라 막는다. 돌아오려면 원래 소셜 계정으로 와야 하고,
     * 그 경로는 가입 요청에서 복원으로 처리된다.
     */
    @Query("""
            select u from User u
            where u.installationId = :installationId
              and u.status = com.ruleup.ruleup_backend.user.domain.UserStatus.WITHDRAWN
            order by u.deletedAt desc
            limit 1
            """)
    Optional<User> findWithdrawnHolderOfInstallation(@Param("installationId") String installationId);
}
