package com.ruleup.ruleup_backend.sanction.domain;

import com.ruleup.ruleup_backend.common.AssignedIdEntity;
import com.ruleup.ruleup_backend.common.UuidGenerator;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 영구 정지 계정의 재가입 차단 ({@code ban_list}) — 온보딩 테크 스펙 5-3.
 *
 * <p>{@code users.status} 만으로는 막을 수 없다. 탈퇴 1년이 지나 계정 행이 파기돼도 차단은
 * 유지돼야 하므로 <b>계정과 생명주기가 분리된 해시</b>만 남긴다. 그래서 {@code user_id} FK 가 없다.
 *
 * <p>원본 식별자는 보관하지 않는다 — 솔트 HMAC 이라 역산할 수 없다.
 */
@Entity
@Table(name = "ban_list")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BanEntry extends AssignedIdEntity {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** {@code HMAC(salt, provider + ':' + subject)} — 소셜 계정 차단. */
    @Column(name = "oauth_hash", nullable = false, length = 64)
    private String oauthHash;

    /** 소셜 계정을 바꿔 우회하는 경로를 막는 보조 차단. */
    @Column(name = "installation_hash", length = 64)
    private String installationHash;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "sanction_id")
    private UUID sanctionId;

    @Column(name = "banned_at", nullable = false)
    private Instant bannedAt;

    public static BanEntry of(String oauthHash, String installationHash, UUID sanctionId, Instant at) {
        BanEntry e = new BanEntry();
        e.id = UuidGenerator.generate();
        e.oauthHash = oauthHash;
        e.installationHash = installationHash;
        e.sanctionId = sanctionId;
        e.bannedAt = at;
        return e;
    }
}
