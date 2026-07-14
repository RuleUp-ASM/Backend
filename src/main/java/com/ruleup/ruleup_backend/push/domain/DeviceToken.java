package com.ruleup.ruleup_backend.push.domain;

import com.ruleup.ruleup_backend.common.AssignedIdEntity;
import com.ruleup.ruleup_backend.common.UuidGenerator;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * FCM 등록 토큰 1건(User N:1). 한 유저가 폰·워치 등 여러 디바이스 토큰을 가질 수 있다.
 *  - token 은 유니크. 같은 토큰이 다른 유저로 재등록되면(기기 양도/재로그인) 소유자를 재바인딩한다.
 *  - 전송 실패(UNREGISTERED)로 무효화된 토큰은 전송 어댑터가 정리한다.
 * 연관관계 대신 raw userId(BINARY(16)) 만 보유(verification/watcher 도메인과 동일 패턴).
 */
@Entity
@Table(name = "DeviceToken")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeviceToken extends AssignedIdEntity {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "userId", nullable = false)
    private UUID userId;

    @Column(name = "token", nullable = false, updatable = false, length = 512)
    private String token;

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false)
    private DevicePlatform platform;

    @Column(name = "lastSeenAt", nullable = false)
    private Instant lastSeenAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "createdAt", nullable = false, updatable = false)
    private Instant createdAt;

    public static DeviceToken create(UUID userId, String token, DevicePlatform platform, Instant now) {
        DeviceToken t = new DeviceToken();
        t.id = UuidGenerator.generate();
        t.userId = userId;
        t.token = token;
        t.platform = platform;
        t.lastSeenAt = now;
        return t;
    }

    /** 기존 토큰의 소유자·플랫폼 재바인딩 + 최근 확인 시각 갱신(재등록 시). */
    public void reassign(UUID userId, DevicePlatform platform, Instant at) {
        this.userId = userId;
        this.platform = platform;
        this.lastSeenAt = at;
    }
}
