package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.common.UuidGenerator;
import com.ruleup.ruleup_backend.verification.evaluator.TimeWindows;
import com.ruleup.ruleup_backend.verification.signal.SyncSignal;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 신호 수신 — 원본 저장 + 영속 멱등 (백엔드 테크스펙 §4-3 "신호 수신").
 *
 * <p>같은 신호가 여러 번 도착하는 것은 정상 경로다. 오프라인 복구, FCM 기동 후 일괄 전송, 구간 재전송이
 * 전부 재전송을 만든다. 중복을 받아들이되 <b>판정에는 한 번만</b> 반영해야 하는데, 이 경계를 평가기 안의
 * 메모리 상태나 evidence 에 맡기면 평가기마다 다시 구현해야 하고 한 곳이라도 빠지면 사용 시간이 두 배가 된다.
 * 그래서 수신 지점에서 DB 유일성으로 끊는다.
 *
 * <p>멱등 키는 클라가 준 {@code recordId} 가 1순위, 없으면 신호 내용 전체의 해시다.
 * 내용 해시는 계약에 선언된 필드로만 만들어지므로, 필드가 늘어나면 예전 신호와 키가 달라질 수 있다 —
 * 그 경우 배포 직후 한 번 중복이 통과할 수 있고 그 이후로는 새 키로 안정화된다.
 */
@Service
@RequiredArgsConstructor
public class VerificationSignalIngestService {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 한 번에 보관할 신호 수 상한(방어적). 바이트 상한은 요청 필터가 먼저 끊는다. */
    private static final int INSERT_BATCH = 500;

    private final JdbcTemplate jdbc;

    /**
     * 수신 결과.
     *
     * @param accepted     이번에 처음 받은 신호 — 판정 입력으로 넘긴다
     * @param droppedCount 중복으로 걸러낸 신호 수(sync_result 로깅 입력)
     */
    public record Ingested(List<SyncSignal> accepted, int droppedCount) {}

    /**
     * 신호를 원본 그대로 저장하고, 처음 받은 것만 골라 돌려준다.
     *
     * <p>같은 유저가 동시에 sync 를 치면 아주 드물게 같은 신호가 양쪽에서 "처음"으로 보일 수 있다.
     * 저장은 UNIQUE 가 막고(INSERT IGNORE), 판정 쪽은 평가기의 누적 워터마크가 한 번 더 막는다.
     */
    @Transactional
    public Ingested ingest(UUID userId, List<SyncSignal> signals, Instant receivedAt) {
        if (signals == null || signals.isEmpty()) return new Ingested(List.of(), 0);

        // 한 요청 안의 중복부터 접는다 — 같은 배치에 같은 신호가 두 번 실려 오는 일이 흔하다.
        Map<String, SyncSignal> byKey = new LinkedHashMap<>();
        int dropped = 0;
        for (SyncSignal s : signals) {
            if (s == null) continue;
            if (byKey.putIfAbsent(dedupKey(s), s) != null) dropped++;
        }
        if (byKey.isEmpty()) return new Ingested(List.of(), dropped);

        Set<String> known = alreadyStored(userId, byKey.keySet());
        List<SyncSignal> accepted = new ArrayList<>();
        List<Object[]> rows = new ArrayList<>();
        for (Map.Entry<String, SyncSignal> e : byKey.entrySet()) {
            if (known.contains(e.getKey())) { dropped++; continue; }
            accepted.add(e.getValue());
            rows.add(row(userId, e.getKey(), e.getValue(), receivedAt));
        }
        insertAll(rows);
        return new Ingested(accepted, dropped);
    }

    /** 이미 저장된 키. 요청 크기가 상한으로 묶여 있어 IN 절 크기도 함께 묶인다. */
    private Set<String> alreadyStored(UUID userId, Set<String> keys) {
        Set<String> found = new HashSet<>();
        List<String> all = new ArrayList<>(keys);
        for (int from = 0; from < all.size(); from += INSERT_BATCH) {
            List<String> chunk = all.subList(from, Math.min(from + INSERT_BATCH, all.size()));
            String placeholders = String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));
            List<Object> args = new ArrayList<>();
            args.add(bytes(userId));
            args.addAll(chunk);
            found.addAll(jdbc.queryForList(
                    "SELECT dedupKey FROM verification_signals WHERE userId = ? AND dedupKey IN (" + placeholders + ")",
                    String.class, args.toArray()));
        }
        return found;
    }

    /**
     * INSERT IGNORE 로 적재한다 — 동시 요청이 같은 키를 넣어도 예외 없이 한 건만 남는다.
     * 예외로 처리하면 트랜잭션이 롤백 표시돼 나머지 신호까지 잃는다.
     */
    private void insertAll(List<Object[]> rows) {
        for (int from = 0; from < rows.size(); from += INSERT_BATCH) {
            List<Object[]> chunk = rows.subList(from, Math.min(from + INSERT_BATCH, rows.size()));
            jdbc.batchUpdate("INSERT IGNORE INTO verification_signals " +
                    "(id, userId, dedupKey, signalType, observedAt, receivedAt, payload) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)", chunk);
        }
    }

    private Object[] row(UUID userId, String key, SyncSignal signal, Instant receivedAt) {
        Instant observedAt = TimeWindows.parseInstant(signal.observedAt());
        return new Object[]{
                bytes(UuidGenerator.generate()), bytes(userId), key,
                (signal.type() != null) ? signal.type() : "UNKNOWN",
                (observedAt != null) ? Timestamp.from(observedAt) : null,
                Timestamp.from(receivedAt),
                JSON.writeValueAsString(signal)};
    }

    /** recordId 가 있으면 그것으로, 없으면 신호 내용 전체로 만든 해시. */
    private String dedupKey(SyncSignal signal) {
        String source = (signal.recordId() != null && !signal.recordId().isBlank())
                ? "rec:" + signal.recordId().trim()
                : "sig:" + JSON.writeValueAsString(signal);
        return sha256Hex(source);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 미지원", e);   // 표준 JDK 에서는 발생하지 않는다
        }
    }

    private static byte[] bytes(UUID id) {
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(id.getMostSignificantBits());
        bb.putLong(id.getLeastSignificantBits());
        return bb.array();
    }
}
