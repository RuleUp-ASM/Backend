package com.ruleup.ruleup_backend.common.outbox;

import com.ruleup.ruleup_backend.common.UuidGenerator;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 발행 대기함 한 줄 ({@code outbox_messages}).
 *
 * <p><b>이 행은 도메인 트랜잭션과 같은 커밋에 들어간다.</b> 그것이 존재 이유의 전부다 —
 * 도메인이 롤백되면 발행 의사도 함께 사라지고, 커밋됐으면 서버가 그 직후 죽어도 행이 남아
 * 스윕이 반드시 줍는다.
 *
 * <p>페이로드는 <b>엔티티 참조가 아니라 스냅샷</b>이다. 디스패처가 도는 시점에 원본이 바뀌어
 * 있어도 발행 내용은 커밋 당시의 값이어야 한다 — 제재 사유가 수정됐다고 이미 확정된 고지의
 * 문구가 달라지면 안 된다.
 */
@Entity
@Table(name = "outbox_messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxMessage {

    /** 재시도 상한. 넘으면 더 집지 않고 last_error 를 남긴 채 묻는다 — 무한 재시도가 큐를 막는다. */
    public static final int MAX_ATTEMPTS = 5;

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "type", nullable = false, length = 40, updatable = false)
    private String type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    @Column(name = "dedup_key", length = 160, updatable = false)
    private String dedupKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    /** null 이면 미처리 — 스윕이 이 값으로 남은 일을 찾는다. */
    @Column(name = "processed_at")
    private Instant processedAt;

    /**
     * 재시도 상한을 넘겨 <b>포기한</b> 시각. {@code processedAt} 과 나눠 둔 이유는 「나갔다」와
     * 「끝내 못 나갔다」가 집계에서 같은 모양이 되면 안 되기 때문이다.
     */
    @Column(name = "dead_lettered_at")
    private Instant deadLetteredAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 500)
    private String lastError;

    public static OutboxMessage of(String type, String payload, String dedupKey, Instant now) {
        OutboxMessage m = new OutboxMessage();
        m.id = UuidGenerator.generate();
        m.type = type;
        m.payload = payload;
        m.dedupKey = dedupKey;
        m.createdAt = now;
        m.availableAt = now;
        return m;
    }

    public void markProcessed(Instant at) {
        this.processedAt = at;
        this.lastError = null;
    }

    /**
     * 실패 기록 — 재시도 여지가 있으면 백오프를 걸고, 상한을 넘으면 <b>포기</b>로 표시한다.
     *
     * <p>상한을 넘긴 건을 미처리로 남겨 두면 스윕이 영원히 같은 행을 다시 집어 뒤에 쌓인
     * 정상 건까지 굶는다. 그렇다고 {@code processedAt} 을 찍어 닫으면 발행된 건과 구분이
     * 사라져, 감시자 통지나 강퇴가 조용히 없어져도 집계는 정상으로 보인다. 그래서 별도
     * 시각으로 남긴다 — 스윕은 집지 않고, 운영은 찾아서 되살릴 수 있다.
     */
    public void markFailed(Instant at, String error) {
        this.attempts++;
        this.lastError = truncate(error);
        if (attempts >= MAX_ATTEMPTS) {
            this.deadLetteredAt = at;   // 포기 — 더 집지 않되 발행된 것으로 세지도 않는다
            return;
        }
        // 지수 백오프: 1분 → 2 → 4 → 8분. 외부 장애가 원인일 때 몰아치지 않게 한다.
        this.availableAt = at.plus(Duration.ofMinutes(1L << (attempts - 1)));
    }

    /**
     * 포기한 메시지를 다시 줄에 세운다. 같은 사건이 다시 적재될 때와 운영이 직접 되살릴 때
     * 모두 이 경로를 쓴다 — {@code dedupKey} 가 남아 있어 새 행을 만들 수 없기 때문이다.
     *
     * <p>{@code processedAt} 도 함께 비운다. 포기를 {@code processed_at} 으로 닫던 시절의 행은
     * 두 시각이 <b>모두</b> 차 있는데, 포기 표식만 지우면 {@link #isPending()} 이 여전히 false 라
     * 스윕이 집지 않는다 — 죽은 목록에서는 사라지고 발행은 되지 않는, 되살릴 수조차 없는
     * 상태가 된다. 되살린다는 것은 <b>아직 발행되지 않았다</b>고 선언하는 일이다.
     */
    public void redrive(Instant at) {
        this.deadLetteredAt = null;
        this.processedAt = null;
        this.attempts = 0;
        this.availableAt = at;
    }

    public boolean isPending() {
        return processedAt == null && deadLetteredAt == null;
    }

    /** 끝내 발행되지 못한 채 닫혔는지. */
    public boolean isDeadLettered() {
        return deadLetteredAt != null;
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= 500 ? s : s.substring(0, 500);
    }
}
