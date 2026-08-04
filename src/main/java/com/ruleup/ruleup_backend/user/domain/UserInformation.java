package com.ruleup.ruleup_backend.user.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 사용자 개인정보 (user_information 테이블). users 와 1:1 (공유 PK).
 * PII(생일·성별·이메일)를 계정 코어(users)와 분리해 추후 탈퇴 아카이브/파기 구현에 대비한다.
 * 생성/수정은 {@link User}를 통해서만 한다 (cascade).
 */
@Entity
@Table(name = "user_information")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserInformation {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "user_id")
    private User user;

    /** 생년월일. 가입 후 수정 불가(계약). 레거시 온보딩 경로 호환을 위해 DB는 NULL 허용. */
    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "gender")
    private Gender gender;

    @Column(name = "email")
    private String email;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    static UserInformation of(User user, String email) {
        UserInformation info = new UserInformation();
        info.user = user;
        info.userId = user.getId();
        info.email = email;
        return info;
    }

    void updateBirthDate(LocalDate birthDate) {
        if (birthDate != null) this.birthDate = birthDate;
    }

    void updateGender(Gender gender) {
        if (gender != null) this.gender = gender;
    }
}
