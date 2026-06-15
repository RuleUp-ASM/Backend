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

    /** 닉네임 LLM 검수 상태 ("확인 전/후"). 가입은 항상 PENDING으로 통과 후 비동기 검수. */
    @Enumerated(EnumType.STRING)
    @Column(name = "nickname_status", nullable = false)
    private ModerationStatus nicknameStatus = ModerationStatus.PENDING;

    /**
     * 검수 통과 전(또는 거절)에 다른 사용자에게 대신 보여줄 임시 닉네임 (예: user_ab12cd).
     * 본인에게는 항상 본인이 정한 nickname이 보인다.
     */
    @Column(name = "temp_nickname", nullable = false, length = 20)
    private String tempNickname;

    @Column(name = "profile_image_url")
    private String profileImageUrl;

    /** 프로필 사진 LLM 검수 상태 ("확인 전/후"). 사진이 없으면 숨길 것도 없어 APPROVED. */
    @Enumerated(EnumType.STRING)
    @Column(name = "profile_image_status", nullable = false)
    private ModerationStatus profileImageStatus = ModerationStatus.PENDING;

    /** 마지막으로 LLM 검수를 실제 수행한 시각 (보류/재시도 판단용). */
    @Column(name = "moderation_checked_at")
    private Instant moderationCheckedAt;

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
        u.tempNickname = generateTempNickname(u.id);
        u.profileImageUrl = profileImageUrl;
        u.interestCategories = (interestCategories != null) ? interestCategories : new ArrayList<>();
        // 가입은 막지 않는다: 닉네임은 항상 검수 대기, 사진은 있으면 검수 대기/없으면 숨길 게 없어 승인.
        u.nicknameStatus = ModerationStatus.PENDING;
        u.profileImageStatus = (profileImageUrl != null) ? ModerationStatus.PENDING : ModerationStatus.APPROVED;
        return u;
    }

    /** UUID에서 안정적으로 파생한 임시 닉네임(예: user_ab12cd). 사람이 봐도 사람마다 다르다. */
    private static String generateTempNickname(UUID id) {
        return "user_" + id.toString().replace("-", "").substring(0, 6);
    }

    public void changeNickname(String newNickname) {
        this.nickname = newNickname;
        this.nicknameChangedAt = Instant.now();
        this.nicknameStatus = ModerationStatus.PENDING;   // 바뀐 닉네임은 다시 검수받아야 한다
        this.moderationCheckedAt = null;
    }

    // ===== 검수 결과 반영 =====
    public void approveNickname()      { this.nicknameStatus = ModerationStatus.APPROVED; }
    public void rejectNickname()       { this.nicknameStatus = ModerationStatus.REJECTED; }
    public void approveProfileImage()  { this.profileImageStatus = ModerationStatus.APPROVED; }
    public void rejectProfileImage()   { this.profileImageStatus = ModerationStatus.REJECTED; }
    public void markModerationChecked(){ this.moderationCheckedAt = Instant.now(); }

    /** 닉네임 검수가 아직 안 끝났는지(보류 포함). */
    public boolean isNicknamePending()     { return nicknameStatus == ModerationStatus.PENDING; }
    /** 사진이 있고 검수가 아직 안 끝났는지. */
    public boolean isProfileImagePending() { return profileImageUrl != null && profileImageStatus == ModerationStatus.PENDING; }

    /**
     * 특정 viewer에게 보여줄 닉네임.
     * 본인이면 항상 본인 닉네임, 타인이면 APPROVED일 때만 본인 닉네임·아니면 임시 닉네임.
     */
    public String visibleNicknameTo(UUID viewerId) {
        if (viewerId != null && viewerId.equals(this.id)) return nickname;
        return (nicknameStatus == ModerationStatus.APPROVED) ? nickname : tempNickname;
    }

    /** 특정 viewer에게 보여줄 프로필 사진. 타인에게는 APPROVED일 때만 노출(아니면 null=기본사진). */
    public String visibleProfileImageTo(UUID viewerId) {
        if (viewerId != null && viewerId.equals(this.id)) return profileImageUrl;
        return (profileImageStatus == ModerationStatus.APPROVED) ? profileImageUrl : null;
    }

    public void softDelete() {
        this.deletedAt = Instant.now();
    }

    /** 관심 카테고리 교체 */
    public void changeInterestCategories(List<String> categories) {
        this.interestCategories = (categories != null) ? new ArrayList<>(categories) : new ArrayList<>();
    }

    /** 프로필 이미지 URL 설정/해제 */
    public void changeProfileImage(String url) {
        this.profileImageUrl = url;
        // 새 사진은 다시 검수, 사진이 없으면(=null) 숨길 게 없어 즉시 승인.
        this.profileImageStatus = (url != null) ? ModerationStatus.PENDING : ModerationStatus.APPROVED;
    }

    public void removeProfileImage() {
        this.profileImageUrl = null;
        this.profileImageStatus = ModerationStatus.APPROVED;
    }
}