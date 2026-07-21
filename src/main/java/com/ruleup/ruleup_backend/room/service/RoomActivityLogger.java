package com.ruleup.ruleup_backend.room.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruleup.ruleup_backend.room.domain.RoomActivityLog;
import com.ruleup.ruleup_backend.room.domain.RoomLogAction;
import com.ruleup.ruleup_backend.room.repository.RoomActivityLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * 방 내부 기록 활동 로그 기록기. 방 내부 기록(공지 등)의 CRUD를 append-only 로그로 남긴다.
 * payload는 삭제 후에도 원본을 확인할 수 있도록 스냅샷(제목/본문 등)을 JSON으로 보관한다.
 */
@Component
@RequiredArgsConstructor
public class RoomActivityLogger {

    /** 컨텍스트에 ObjectMapper 빈이 없어 로컬 인스턴스 사용(스레드 안전). */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RoomActivityLogRepository repository;

    public void log(UUID challengeId, UUID actorId, String entityType, UUID entityId,
                    RoomLogAction action, Map<String, ?> payload) {
        repository.save(RoomActivityLog.of(
                challengeId, actorId, entityType, entityId, action, toJson(payload)));
    }

    private String toJson(Map<String, ?> payload) {
        if (payload == null || payload.isEmpty()) return null;
        try {
            return OBJECT_MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return null;   // 로그 직렬화 실패가 본 트랜잭션을 막지 않도록 방어
        }
    }
}
