package com.ruleup.ruleup_backend.watcher.domain;

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
 * 감시 관계 본체 ({@code watcher_relations}) — 패널티 감시자 공통 5-3.
 *
 * <p><b>(챌린지, 피감시자, 감시자) 3중 키</b>다. 같은 사람을 여러 챌린지에서 감시자로 둘 수 있고
 * 관계는 서로 독립이다.
 *
 * <p>이 엔티티가 지키는 것은 하나 — <b>{@code acceptedAt} 이 동의 시각이며 입증 책임의 근거</b>다.
 * null 이면 PENDING 이고 <b>발송 대상이 아니다</b>. 클라이언트가 "수락했다"고 주장하는 것으로는
 * 전이하지 않으며, 서버가 토큰 검증과 로그인 확인을 마친 뒤에만 {@link #accept} 를 호출한다.
 *
 * <p>감시자는 <b>방 멤버가 아니다.</b> 방 내부 권한 모델과 무관한 별도 관계이며, 통지에 담기는
 * 3개 필드 외에는 아무것도 보여주지 않는다.
 */
@Entity
@Table(name = "watcher_relations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WatcherRelation extends AssignedIdEntity {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "challenge_id", nullable = false, updatable = false)
    private UUID challengeId;

    /** 감시를 받는 참여자 — 초대한 본인. */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "target_user_id", nullable = false, updatable = false)
    private UUID targetUserId;

    /** 감시자 — <b>룰업 앱 유저만</b> 가능하다. 비유저 감시자 개념은 폐지됐다. */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "watcher_user_id", nullable = false, updatable = false)
    private UUID watcherUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private WatcherRelationStatus status;

    /**
     * 푸시 수신 토글. 관계를 끊지 않고 통지만 닫는 스위치다.
     *
     * <p>스펙 5-3의 표에는 없는 컬럼이지만 API #6(수신 토글)의 <b>현재 상태를 담을 곳</b>이
     * 필요하다. 이력({@link WatcherConsentLog})에서 파생하면 발송 대상 조회가 매번 로그
     * 테이블을 훑어야 해서, "PENDING 발송 0건을 성능 문제 없이 지킨다"는 인덱스 설계 근거가
     * 깨진다. 그래서 동의 체계와 같은 방식으로 <b>상태는 여기, 이력은 로그</b>로 나눴다.
     */
    @Column(name = "push_enabled", nullable = false)
    private boolean pushEnabled;

    @Column(name = "invited_at", nullable = false, updatable = false)
    private Instant invitedAt;

    /** <b>동의 시각 — 입증 책임의 근거.</b> null 이면 PENDING 이며 발송 대상이 아니다. */
    @Column(name = "accepted_at")
    private Instant acceptedAt;

    /** 루틴 종료 시 배치가 채운다 — <b>유저가 끊는 경로는 없다</b>. */
    @Column(name = "removed_at")
    private Instant removedAt;

    public static WatcherRelation accepted(UUID challengeId, UUID targetUserId, UUID watcherUserId,
                                           Instant invitedAt, Instant acceptedAt) {
        WatcherRelation r = new WatcherRelation();
        r.id = UuidGenerator.generate();
        r.challengeId = challengeId;
        r.targetUserId = targetUserId;
        r.watcherUserId = watcherUserId;
        r.status = WatcherRelationStatus.ACTIVE;
        r.pushEnabled = true;
        r.invitedAt = invitedAt;
        r.acceptedAt = acceptedAt;
        return r;
    }

    /** 동의 성립 — 서버가 토큰과 로그인을 모두 확인한 뒤에만 부른다. */
    public void accept(Instant at) {
        this.status = WatcherRelationStatus.ACTIVE;
        this.acceptedAt = at;
    }

    public void togglePush(boolean enabled) {
        this.pushEnabled = enabled;
    }

    /** 루틴 종료 자동 제거. 이 배치의 정확도가 곧 수신거부권이다. */
    public void remove(Instant at) {
        if (removedAt == null) this.removedAt = at;
    }

    /** 통지를 보낼 수 있는 상태인지 — 발송 직전에 다시 확인한다. */
    public boolean isDispatchable() {
        return status == WatcherRelationStatus.ACTIVE && removedAt == null && pushEnabled;
    }
}
