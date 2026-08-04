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

    /** 동일 설치(installationId)에 연결된 활성(미탈퇴) 계정 존재 여부 — 다계정 가입 차단(회원 정책 §1). */
    @Query("""
            select count(u) > 0 from User u
            where u.installationId = :installationId
              and u.status <> com.ruleup.ruleup_backend.user.domain.UserStatus.WITHDRAWN
            """)
    boolean existsActiveByInstallationId(@Param("installationId") String installationId);
}
