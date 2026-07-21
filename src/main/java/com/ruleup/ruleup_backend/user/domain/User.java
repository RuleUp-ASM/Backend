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
 * 서비스 사용자 (User 테이블).
 * OAuth(provider+subject)로 사용자를 식별하며, 닉네임은 unique.
 * - 생성은 반드시 정적 팩토리 create(...)를 통한다 (id 자동 채움).
 * - 탈퇴는 실제 삭제가 아니라 softDelete()로 deletedAt만 기록
 * - PK는 UUID v7 → BINARY(16) (시간순 정렬 + 인덱스 지역성)
 */
@Entity
@Table(name = "User")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends AssignedIdEntity {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)               // UUID를 BINARY(16)로 저장 (MySQL)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)                 // MySQL ENUM 컬럼에 문자열로 매핑
    @Column(name = "oauthProvider", nullable = false)
    private OAuthProvider oauthProvider;

    @Column(name = "oauthSubject", nullable = false)
    private String oauthSubject;

    @Column(name = "email")
    private String email;

    @Column(name = "nickname", nullable = false)
    private String nickname;

    /** 닉네임 LLM 검수 상태 ("확인 전/후"). 가입은 항상 PENDING으로 통과 후 비동기 검수. */
    @Enumerated(EnumType.STRING)
    @Column(name = "nicknameStatus", nullable = false)
    private ModerationStatus nicknameStatus = ModerationStatus.PENDING;

    @Column(name = "profileImageUrl")
    private String profileImageUrl;

    /** 프로필 사진 LLM 검수 상태 ("확인 전/후"). 사진이 없으면 숨길 것도 없어 APPROVED. */
    @Enumerated(EnumType.STRING)
    @Column(name = "profileImageStatus", nullable = false)
    private ModerationStatus profileImageStatus = ModerationStatus.PENDING;

    /** 마지막으로 LLM 검수를 실제 수행한 시각 (보류/재시도 판단용). */
    @Column(name = "moderationCheckedAt")
    private Instant moderationCheckedAt;

    @JdbcTypeCode(SqlTypes.JSON)                 // MySQL JSON 컬럼에 List<String> 매핑
    @Column(name = "interestCategories", nullable = false)
    private List<String> interestCategories = new ArrayList<>();

    // ===== 추천 인구통계 (온보딩 수집, 미입력 시 NULL) =====
    /** 국가 코드 ISO 3166-1 alpha-2 (예: "KR"). 추천 콜드스타트 base 세그먼트. */
    @Column(name = "countryCode", length = 2)
    private String countryCode;

    /** 생년월일. 나이/연령대(age band)는 서비스에서 계산(저장 X). */
    @Column(name = "birthDate")
    private LocalDate birthDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "gender")
    private Gender gender;

    // ===== 기기 정보 (가입 시 최초 수집, 로그인마다 갱신; 추천 PLATFORM 세그먼트로 사용) =====
    /** 클라 플랫폼(ANDROID/IOS). 추천 PLATFORM 세그먼트 축. NULL = 미입력. */
    @Enumerated(EnumType.STRING)
    @Column(name = "platform", length = 16)
    private Platform platform;

    /** 앱 버전 코드(정수, 예: 안드로이드 versionCode). */
    @Column(name = "appVersionCode")
    private Integer appVersionCode;

    /** 앱 버전 네임(표시용, 예: "1.2.0"). */
    @Column(name = "appVersionName", length = 32)
    private String appVersionName;

    /** OS 버전(표시용, 예: 안드로이드 "14"). */
    @Column(name = "osVersion", length = 32)
    private String osVersion;

    /** 안드로이드 SDK Int(예: 34). iOS는 null. */
    @Column(name = "sdkInt")
    private Integer sdkInt;

    /** 기기 모델(예: "SM-S921N"). */
    @Column(name = "deviceModel", length = 64)
    private String deviceModel;

    /** 제조사(예: "samsung"). */
    @Column(name = "manufacturer", length = 64)
    private String manufacturer;

    /** 저사양(RAM) 기기 여부. */
    @Column(name = "lowRam")
    private Boolean lowRam;

    /** 기기 정보 마지막 갱신 시각(로그인마다 갱신). */
    @Column(name = "deviceInfoUpdatedAt")
    private Instant deviceInfoUpdatedAt;

    @Column(name = "nicknameChangedAt")
    private Instant nicknameChangedAt;

    @Column(name = "deletedAt")
    private Instant deletedAt;

    @Generated(event = EventType.INSERT)   // INSERT 시 DB의 default now()로 채워지고, 그 값을 다시 읽어옴
    @Column(name = "createdAt", nullable = false, updatable = false)
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
        u.nicknameStatus = ModerationStatus.PENDING;
        u.profileImageStatus = (profileImageUrl != null) ? ModerationStatus.PENDING : ModerationStatus.APPROVED;
        return u;
    }

    /**
     * 추천용 인구통계 설정(가입 후 최초 접속 시 수집, 선택). 전달된 값만 덮어쓴다(미입력=null은 건너뜀).
     * 추천은 채워진 세그먼트만 사용하므로 일부만 입력하거나 전부 건너뛰어도 동작한다.
     * 국가 코드는 사용자 입력이 아니라 서버가 요청에서 해석하므로 {@link #updateCountryCode}로 따로 채운다.
     */
    public void registerDemographics(LocalDate birthDate, Gender gender) {
        if (birthDate != null) this.birthDate = birthDate;
        if (gender != null) this.gender = gender;
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
     * 전체 디바이스 스펙(osVersion·sdkInt·deviceModel·manufacturer·lowRam)을 저장해 로그인 응답에 되돌려준다.
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

    /**
     * 검수 통과 전(또는 거절)에 다른 사용자에게 대신 보여줄 임시 닉네임 (예: user_ab12cd).
     * 본인에게는 항상 본인이 정한 nickname이 보인다.
     * <p>PK(UUID v7)에서 결정적으로 파생하므로 저장할 필요가 없다(DB 매핑 없는 파생 메서드).
     * v7은 앞 구간이 타임스탬프라 같은 시간대 가입자끼리 겹치므로, 랜덤 비트 구간인 <b>뒤 6자리</b>를 쓴다.
     */
    public String tempNickname() {
        String hex = id.toString().replace("-", "");
        return "user_" + hex.substring(hex.length() - 6);
    }

    public void changeNickname(String newNickname) {
        this.nickname = newNickname;
        this.nicknameChangedAt = Instant.now();
        this.nicknameStatus = ModerationStatus.PENDING;
        this.moderationCheckedAt = null;
    }

    // ===== 검수 결과 반영 =====
    public void approveNickname()      { this.nicknameStatus = ModerationStatus.APPROVED; }
    public void rejectNickname()       { this.nicknameStatus = ModerationStatus.REJECTED; }
    public void approveProfileImage()  { this.profileImageStatus = ModerationStatus.APPROVED; }
    public void rejectProfileImage()   { this.profileImageStatus = ModerationStatus.REJECTED; }
    public void markModerationChecked(){ this.moderationCheckedAt = Instant.now(); }

    public boolean isNicknamePending()     { return nicknameStatus == ModerationStatus.PENDING; }
    public boolean isProfileImagePending() { return profileImageUrl != null && profileImageStatus == ModerationStatus.PENDING; }

    public String visibleNicknameTo(UUID viewerId) {
        if (viewerId != null && viewerId.equals(this.id)) return nickname;
        return (nicknameStatus == ModerationStatus.APPROVED) ? nickname : tempNickname();
    }

    public String visibleProfileImageTo(UUID viewerId) {
        if (viewerId != null && viewerId.equals(this.id)) return profileImageUrl;
        return (profileImageStatus == ModerationStatus.APPROVED) ? profileImageUrl : null;
    }

    public void softDelete() {
        this.deletedAt = Instant.now();
    }

    public void changeInterestCategories(List<String> categories) {
        this.interestCategories = (categories != null) ? new ArrayList<>(categories) : new ArrayList<>();
    }

    public void changeProfileImage(String url) {
        this.profileImageUrl = url;
        this.profileImageStatus = (url != null) ? ModerationStatus.PENDING : ModerationStatus.APPROVED;
    }

    public void removeProfileImage() {
        this.profileImageUrl = null;
        this.profileImageStatus = ModerationStatus.APPROVED;
    }
}