package com.ruleup.ruleup_backend.challenge.creation;

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
 * 챌린지 이미지 업로드 소유 기록(challenge_image_uploads).
 * 생성·수정 API 는 "업로드 API 가 현재 사용자에게 발급한 우리 스토리지 객체"만 허용한다 —
 * 임의 외부 URL 주입·타 사용자 객체 재사용 차단. 미등록(registered_at IS NULL) 업로드는
 * 24시간 후 정리 배치가 삭제한다(고아 파일 누적 방지).
 */
@Entity
@Table(name = "challenge_image_uploads")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChallengeImageUpload {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "image_url", nullable = false, length = 500, updatable = false)
    private String imageUrl;

    @Column(name = "registered_at")
    private Instant registeredAt;       // 챌린지에 실제 등록된 시각(NULL = 미등록)

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static ChallengeImageUpload of(UUID userId, String imageUrl) {
        ChallengeImageUpload u = new ChallengeImageUpload();
        u.userId = userId;
        u.imageUrl = imageUrl;
        return u;
    }

    public boolean ownedBy(UUID userId) { return this.userId.equals(userId); }

    public void markRegistered(Instant at) { this.registeredAt = at; }
}
