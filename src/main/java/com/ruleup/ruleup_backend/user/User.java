package com.ruleup.ruleup_backend.user;

import com.ruleup.ruleup_backend.common.UuidGenerator;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import com.ruleup.ruleup_backend.common.AssignedIdEntity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 서비스 사용자 (users 테이블).
 * OAuth(provider+subject)로 사용자를 식별하며, 닉네임은 unique.
 * - 생성은 반드시 정적 팩토리 create(...)를 통한다 (id 자동 채움).
 * - 탈퇴는 실제 삭제가 아니라 softDelete()로 deleted_at만 기록
 */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends AssignedIdEntity {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)                 // UUID를 CHAR(36) 문자열로 저장 (MySQL)
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private UUID id;

    @Enumerated(EnumType.STRING)                 // MySQL ENUM 컬럼에 문자열로 매핑
    @Column(name = "oauth_provider", nullable = false)
    private OAuthProvider oauthProvider;

    @Column(name = "oauth_subject", nullable = false)
    private String oauthSubject;

    @Column(name = "email")
    private String email;

    @Column(name = "nickname", nullable = false)
    private String nickname;

    @Column(name = "profile_image_url")
    private String profileImageUrl;

    @JdbcTypeCode(SqlTypes.JSON)                 // MySQL JSON 컬럼에 List<String> 매핑
    @Column(name = "interest_categories", nullable = false)
    private List<String> interestCategories = new ArrayList<>();

    @Column(name = "nickname_changed_at")
    private Instant nicknameChangedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Generated(event = EventType.INSERT)   // INSERT 시 DB의 default now()로 채워지고, 그 값을 다시 읽어옴
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static User create(OAuthProvider provider, String oauthSubject, String email,
                              String nickname, String profileImageUrl,
                              List<String> interestCategories) {
        User u = new User();
        u.id = UuidGenerator.generate();
        u.oauthProvider = provider;
        u.oauthSubject = oauthSubject;
        u.email = email;
        u.nickname = nickname;
        u.profileImageUrl = profileImageUrl;
        u.interestCategories = (interestCategories != null) ? interestCategories : new ArrayList<>();
        return u;
    }

    public void changeNickname(String newNickname) {
        this.nickname = newNickname;
        this.nicknameChangedAt = Instant.now();
    }

    public void softDelete() {
        this.deletedAt = Instant.now();
    }

    /** 관심 카테고리 교체 */
    public void changeInterestCategories(List<String> categories) {
        this.interestCategories = (categories != null) ? new ArrayList<>(categories) : new ArrayList<>();
    }

    /** 프로필 이미지 URL 설정/해제 */
    public void changeProfileImage(String url) { this.profileImageUrl = url; }
    public void removeProfileImage() { this.profileImageUrl = null; }
}