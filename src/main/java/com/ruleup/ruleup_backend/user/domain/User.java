package com.ruleup.ruleup_backend.user.domain;

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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 서비스 사용자 (users 테이블 — DB 정리 문서 스키마).
 * OAuth(provider+subject)로 식별. 개인정보(생일·성별·이메일)는 user_information(1:1)으로 분리.
 * - 생성은 반드시 정적 팩토리 create(...)를 통한다 (id 자동 채움).
 * - 탈퇴는 소프트 탈퇴: status=WITHDRAWN + deleted_at 기록 (withdraw()).
 * - 닉네임 2컬럼 모델: nickname(신청값, 본인 화면) / approvedNickname(타인에게 항상 노출).
 *   최초 가입 직후 approvedNickname 은 UUID 기반 임시 8자리.
 * - PK는 UUID v7 → BINARY(16).
 */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends AssignedIdEntity {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)               // UUID를 BINARY(16)로 저장 (MySQL)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "oauth_provider", nullable = false)
    private OAuthProvider oauthProvider;

    /** 탈퇴 1년 후 최종 파기 시 NULL로 익명화할 수 있어 nullable. 그 전까지는 항상 존재. */
    @Column(name = "oauth_subject")
    private String oauthSubject;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private UserStatus status = UserStatus.ACTIVE;

    /** 사용자가 신청한 닉네임(본인 화면 표시용). 변경 시 새 신청값으로 교체된다. */
    @Column(name = "nickname", nullable = false)
    private String nickname;

    /** 다른 사용자에게 항상 노출되는 닉네임. 최초 가입 직후엔 UUID 기반 임시 8자리. */
    @Column(name = "approved_nickname", nullable = false)
    private String approvedNickname;

    @Enumerated(EnumType.STRING)
    @Column(name = "nickname_status", nullable = false)
    private NicknameStatus nicknameStatus = NicknameStatus.PENDING;

    /** 실제 승인 닉네임이 마지막으로 변경된 시각(거절 후 재신청만으로는 갱신 안 함). */
    @Column(name = "nickname_changed_at")
    private Instant nicknameChangedAt;

    /** 사용자가 현재 제출한 이미지 (PENDING/REJECTED 상태일 수 있음). */
    @Column(name = "profile_image_url")
    private String profileImageUrl;

    /** 다른 사용자에게 실제 노출되는 승인 이미지. NULL이면 기본 프로필. */
    @Column(name = "approved_profile_image_url")
    private String approvedProfileImageUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "profile_image_status", nullable = false)
    private ProfileImageStatus profileImageStatus = ProfileImageStatus.NONE;

    /** 마지막으로 LLM 검수를 실제 수행한 시각 (보류/재시도 판단용). */
    @Column(name = "moderation_checked_at")
    private Instant moderationCheckedAt;

    // ===== 단일 활성 기기 (멀티 디바이스 미지원 — 현재 설치·기기만 저장, 새 로그인 시 덮어씀) =====
    /** 앱 설치 단위 UUID. UNIQUE — 하나의 설치가 여러 계정에 연결되는 것을 방지. */
    @Column(name = "installation_id")
    private String installationId;

    /** 기기 식별자. 단일 활성 기기 판정 키. */
    @Column(name = "device_id")
    private String deviceId;

    /** 사용자 개인정보(생일·성별·이메일) — user_information 1:1. 조회 편의를 위해 EAGER. */
    @OneToOne(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private UserInformation information;

    /** 관심 카테고리 0~6개 (user_interests 테이블). 응답 조립이 잦아 EAGER. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_interests", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "category", nullable = false)
    private List<String> interestCategories = new ArrayList<>();

    // ===== 추천 인구통계/기기 스펙 (문서 device_info JSON 대신 구조화 컬럼 유지) =====
    /** 국가 코드 ISO 3166-1 alpha-2 (예: "KR"). 추천 콜드스타트 base 세그먼트. */
    @Column(name = "country_code", length = 2)
    private String countryCode;

    /** 클라 플랫폼(ANDROID/IOS). 추천 PLATFORM 세그먼트 축. NULL = 미입력. */
    @Enumerated(EnumType.STRING)
    @Column(name = "platform", length = 16)
    private Platform platform;

    @Column(name = "app_version_code")
    private Integer appVersionCode;

    @Column(name = "app_version_name", length = 32)
    private String appVersionName;

    @Column(name = "os_version", length = 32)
    private String osVersion;

    /** 안드로이드 SDK Int(예: 34). iOS는 null. */
    @Column(name = "sdk_int")
    private Integer sdkInt;

    @Column(name = "device_model", length = 64)
    private String deviceModel;

    @Column(name = "manufacturer", length = 64)
    private String manufacturer;

    /** 저사양(RAM) 기기 여부. */
    @Column(name = "low_ram")
    private Boolean lowRam;

    /** 기기 정보 마지막 갱신 시각(로그인마다 갱신). */
    @Column(name = "device_info_updated_at")
    private Instant deviceInfoUpdatedAt;

    @Generated(event = EventType.INSERT)   // INSERT 시 DB default now()
    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    /** 휴면 판정 기준 — 인증된 API 호출/데이터 제출 시 갱신(하루 1회). */
    @Generated(event = EventType.INSERT)
    @Column(name = "last_active_at")
    private Instant lastActiveAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static User create(OAuthProvider provider, String oauthSubject, String email,
                              String nickname, String profileImageUrl,
                              List<String> interestCategories) {
        User u = new User();
        u.id = UuidGenerator.generate();
        u.oauthProvider = provider;
        u.oauthSubject = oauthSubject;
        u.nickname = nickname;
        u.approvedNickname = u.deriveTempNickname();   // 승인 전 타인 노출용 임시 닉네임
        u.nicknameStatus = NicknameStatus.PENDING;
        u.profileImageUrl = profileImageUrl;
        u.approvedProfileImageUrl = null;              // 승인 전까지 기본 프로필
        u.profileImageStatus = (profileImageUrl != null) ? ProfileImageStatus.PENDING : ProfileImageStatus.NONE;
        u.interestCategories = (interestCategories != null) ? new ArrayList<>(interestCategories) : new ArrayList<>();
        u.information = UserInformation.of(u, email);
        return u;
    }

    // ===== 개인정보(user_information) 위임 접근자 =====
    public String getEmail()       { return (information != null) ? information.getEmail() : null; }
    public LocalDate getBirthDate(){ return (information != null) ? information.getBirthDate() : null; }
    public Gender getGender()      { return (information != null) ? information.getGender() : null; }

    /**
     * 추천용 인구통계 설정(온보딩 수집, 선택). 전달된 값만 덮어쓴다(미입력=null은 건너뜀).
     * 생일은 "가입 후 수정 불가" 계약이므로 이미 값이 있으면 무시한다.
     */
    public void registerDemographics(LocalDate birthDate, Gender gender) {
        if (information == null) information = UserInformation.of(this, null);
        if (getBirthDate() == null) information.updateBirthDate(birthDate);
        information.updateGender(gender);
    }

    /**
     * 국가 코드 갱신(사용자 입력 X — 서버가 요청에서 해석한 값). 가입·로그인마다 최신화.
     * 해석 불가(null)면 기존값을 보존한다.
     */
    public void updateCountryCode(String countryCode) {
        if (countryCode != null) this.countryCode = countryCode;
    }

    /**
     * 기기 정보 갱신(가입 시 최초 수집, 로그인마다 갱신).
     * 전달된 값만 덮어쓴다(부분 전송 시 기존값 보존). 추천 PLATFORM 세그먼트에 platform 사용.
     */
    public void updateDeviceInfo(Platform platform, Integer appVersionCode, String appVersionName,
                                 String osVersion, Integer sdkInt, String deviceModel,
                                 String manufacturer, Boolean lowRam) {
        if (platform != null) this.platform = platform;
        if (appVersionCode != null) this.appVersionCode = appVersionCode;
        if (appVersionName != null) this.appVersionName = appVersionName;
        if (osVersion != null) this.osVersion = osVersion;
        if (sdkInt != null) this.sdkInt = sdkInt;
        if (deviceModel != null) this.deviceModel = deviceModel;
        if (manufacturer != null) this.manufacturer = manufacturer;
        if (lowRam != null) this.lowRam = lowRam;
        this.deviceInfoUpdatedAt = Instant.now();
    }

    /** 단일 활성 기기 등록/교체 — 새 기기 로그인 시 기존 값을 덮어쓴다(기존 RT revoke는 호출측). */
    public void attachInstallation(String installationId, String deviceId) {
        if (installationId != null) this.installationId = installationId;
        if (deviceId != null) this.deviceId = deviceId;
    }

    /** 설치 연결 해제 — 다른 계정이 이 설치를 인계받을 때(uq_users_installation_id 반환). */
    public void detachInstallation() {
        this.installationId = null;
        this.deviceId = null;
    }

    public void touchLastLogin()  { this.lastLoginAt = Instant.now(); }
    public void touchLastActive() { this.lastActiveAt = Instant.now(); }

    /**
     * 승인 전(또는 거절) 타인에게 대신 보여줄 임시 닉네임(UUID 뒤 8자리 hex).
     * PK(UUID v7)에서 파생 — v7 앞 구간은 타임스탬프라 겹치므로 랜덤 비트인 뒤 8자리를 쓴다.
     * (approved_nickname VARCHAR(12) 제약상 prefix 없이 8자만 사용)
     */
    public String deriveTempNickname() {
        String hex = id.toString().replace("-", "");
        return hex.substring(hex.length() - 8);
    }

    /** 임시 닉네임 UNIQUE 충돌 시 재생성용 — 랜덤 UUID 뒤 8자리로 교체. */
    public void regenerateTempApprovedNickname() {
        String hex = UUID.randomUUID().toString().replace("-", "");
        this.approvedNickname = hex.substring(hex.length() - 8);
    }

    public void changeNickname(String newNickname) {
        this.nickname = newNickname;
        this.nicknameStatus = NicknameStatus.PENDING;
        this.moderationCheckedAt = null;
        // approvedNickname(직전 승인본)은 승인 시점까지 유지 — 타인 화면 계속 노출
    }

    // ===== 검수 결과 반영 =====
    public void approveNickname() {
        this.approvedNickname = this.nickname;
        this.nicknameStatus = NicknameStatus.APPROVED;
        this.nicknameChangedAt = Instant.now();
    }

    public void rejectNickname()       { this.nicknameStatus = NicknameStatus.REJECTED; }

    /** 승인 직전 재검사에서 타인이 선점한 경우 (또는 탈퇴 복원 시 선점 충돌). */
    public void markNicknameConflict() { this.nicknameStatus = NicknameStatus.CONFLICT; }

    public void approveProfileImage() {
        this.approvedProfileImageUrl = this.profileImageUrl;
        this.profileImageStatus = ProfileImageStatus.APPROVED;
    }

    public void rejectProfileImage()   { this.profileImageStatus = ProfileImageStatus.REJECTED; }
    public void markModerationChecked(){ this.moderationCheckedAt = Instant.now(); }

    public boolean isNicknamePending()     { return nicknameStatus == NicknameStatus.PENDING; }
    public boolean isProfileImagePending() { return profileImageUrl != null && profileImageStatus == ProfileImageStatus.PENDING; }

    /** 본인에게는 신청 닉네임, 타인에게는 항상 승인 닉네임(approved_nickname). */
    public String visibleNicknameTo(UUID viewerId) {
        if (viewerId != null && viewerId.equals(this.id)) return nickname;
        return approvedNickname;
    }

    /** 본인에게는 제출 이미지, 타인에게는 승인 이미지(없으면 null=기본 프로필). */
    public String visibleProfileImageTo(UUID viewerId) {
        if (viewerId != null && viewerId.equals(this.id)) return profileImageUrl;
        return approvedProfileImageUrl;
    }

    /** 소프트 탈퇴 — status=WITHDRAWN + deleted_at. 기기 연결 해제(installation UNIQUE 반환). */
    public void withdraw() {
        this.status = UserStatus.WITHDRAWN;
        this.deletedAt = Instant.now();
        this.installationId = null;
        this.deviceId = null;
    }

    /** 탈퇴 복원 — 1년 내 동일 소셜 계정 재로그인. 닉네임 충돌 처리는 호출측에서 별도 수행. */
    public void restore(String installationId, String deviceId) {
        this.status = UserStatus.ACTIVE;
        this.deletedAt = null;
        attachInstallation(installationId, deviceId);
    }

    public void lock()  { this.status = UserStatus.LOCKED; }
    public void ban()   { this.status = UserStatus.BANNED; }

    public boolean isWithdrawn() { return status == UserStatus.WITHDRAWN; }
    public boolean isBanned()    { return status == UserStatus.BANNED; }
    public boolean isLocked()    { return status == UserStatus.LOCKED; }

    public void changeInterestCategories(List<String> categories) {
        this.interestCategories.clear();
        if (categories != null) this.interestCategories.addAll(categories);
    }

    public void changeProfileImage(String url) {
        this.profileImageUrl = url;
        if (url != null) {
            this.profileImageStatus = ProfileImageStatus.PENDING;   // 승인 전까지 타인에겐 직전 승인본
        } else {
            this.approvedProfileImageUrl = null;
            this.profileImageStatus = ProfileImageStatus.NONE;
        }
    }

    public void removeProfileImage() {
        this.profileImageUrl = null;
        this.approvedProfileImageUrl = null;
        this.profileImageStatus = ProfileImageStatus.NONE;
    }
}
