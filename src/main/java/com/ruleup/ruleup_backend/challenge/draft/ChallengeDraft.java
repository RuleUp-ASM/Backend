package com.ruleup.ruleup_backend.challenge.draft;

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

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 초안 원본(challenge_drafts) — 서버 발급 draftId 로 24시간 보관.
 *
 * 존재 이유(백엔드 테크 스펙 4-3, P0):
 *  - 심사 우회 차단: 생성 요청의 title/description 을 이 원본과 대조해 "사용자 직접 수정" 여부를
 *    서버가 판정한다(클라이언트 자가 신고 폐기 — 변조 클라가 심사를 우회하던 경로).
 *  - ai_title(대체 표시)·origin(AI/TEMPLATE/CLONE)·복제 출처는 이 행에서 가져오며 클라가 지정할 수 없다.
 * 만료(24h) 건은 라이프사이클 배치가 삭제한다.
 */
@Entity
@Table(name = "challenge_drafts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChallengeDraft extends AssignedIdEntity {

    public static final Duration TTL = Duration.ofHours(24);

    public enum Origin { AI, TEMPLATE, CLONE }

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, updatable = false)
    private Origin origin;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "source_challenge_id", updatable = false)
    private UUID sourceChallengeId;      // CLONE 전용(서버 기록)

    @Column(name = "template_id", updatable = false)
    private Long templateId;

    @Column(name = "title", nullable = false, length = 30)
    private String title;                // 원본 제목(대조 기준·AI 임시 제목)

    @Column(name = "description", length = 200)
    private String description;          // 원본 설명(대조 기준)

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false)
    private DraftPayload payload;        // 초안 전 필드 + 내부 값(repeatDays 등)

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    /** 초안 전 필드(계약 스키마 그대로) + API 로 노출하지 않는 내부 값. */
    public record DraftPayload(DraftView draft, java.util.List<String> repeatDays) {}

    public static ChallengeDraft of(UUID userId, Origin origin, Long templateId,
                                    UUID sourceChallengeId, DraftView draft,
                                    java.util.List<String> repeatDays, Instant now) {
        ChallengeDraft d = new ChallengeDraft();
        d.id = UuidGenerator.generate();
        d.userId = userId;
        d.origin = origin;
        d.templateId = templateId;
        d.sourceChallengeId = sourceChallengeId;
        d.title = draft.title();
        d.description = draft.description();
        d.payload = new DraftPayload(draft, repeatDays);
        d.expiresAt = now.plus(TTL);
        return d;
    }

    public boolean isExpired(Instant now) { return now.isAfter(expiresAt); }
}
