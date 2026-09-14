package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.common.UuidGenerator;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * sync 세션 기록 — 인트로가 발급하고 sync 가 갱신한다 (백엔드 4-4).
 *
 * <p>세션 ID 를 즉석에서 만들어 내려보내기만 하면 아무것도 붙잡지 못한다. 어느 기기가 어떤
 * 수집 정책을 받아 갔는지, 그 뒤로 신호가 계속 오는지를 여기서만 알 수 있다.
 *
 * <p><b>기록 실패가 인트로·sync 를 막지 않는다.</b> 이건 관측·운영 데이터지 판정의 일부가
 * 아니다 — 여기서 예외를 올리면 세션 테이블 장애가 곧 인증 중단이 된다.
 */
@Component
@RequiredArgsConstructor
public class VerificationSyncSessionStore {

    private static final Logger log = LoggerFactory.getLogger(VerificationSyncSessionStore.class);

    private final JdbcTemplate jdbc;

    /** 인트로 발급. 돌려준 ID 가 응답의 sessionId 다. */
    @Transactional
    public UUID issue(UUID userId, String deviceId, String appVersion, Integer sdkInt, Instant at) {
        UUID sessionId = UuidGenerator.generate();
        try {
            jdbc.update("INSERT INTO verification_sync_sessions "
                            + "(id, userId, deviceId, appVersion, sdkInt, issuedAt) VALUES (?, ?, ?, ?, ?, ?)",
                    bytes(sessionId), bytes(userId), trim(deviceId, 64), trim(appVersion, 32), sdkInt,
                    Timestamp.from(at));
        } catch (RuntimeException e) {
            log.warn("sync 세션 발급 기록 실패 — 인트로는 계속한다. userId={} err={}", userId, e.toString());
        }
        return sessionId;
    }

    /**
     * 이 세션으로 신호가 들어왔다. 모르는 세션이면 아무 일도 하지 않는다 —
     * 예전 앱이 보내는 임의 값이나 재설치 전 세션이 여기로 올 수 있고, 그건 오류가 아니다.
     */
    @Transactional
    public void touch(UUID userId, String sessionId, Instant at) {
        UUID parsed = parse(sessionId);
        if (parsed == null) return;
        try {
            jdbc.update("UPDATE verification_sync_sessions SET lastSeenAt = ? WHERE id = ? AND userId = ?",
                    Timestamp.from(at), bytes(parsed), bytes(userId));
        } catch (RuntimeException e) {
            log.warn("sync 세션 갱신 실패 — 판정은 계속한다. userId={} err={}", userId, e.toString());
        }
    }

    private static UUID parse(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String trim(String value, int max) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        return (trimmed.length() <= max) ? trimmed : trimmed.substring(0, max);
    }

    private static byte[] bytes(UUID id) {
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(id.getMostSignificantBits());
        bb.putLong(id.getLeastSignificantBits());
        return bb.array();
    }
}
