package com.ruleup.ruleup_backend.admin.service;

import com.ruleup.ruleup_backend.admin.domain.AdminAction;
import com.ruleup.ruleup_backend.admin.domain.AdminAuditLog;
import com.ruleup.ruleup_backend.admin.repository.AdminAuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 조작 이력 기록.
 *
 * <p><b>{@code REQUIRES_NEW} 로 독립 커밋한다.</b> 집행이 실패해 롤백되더라도 "누가 무엇을
 * 시도했는지"는 남아야 하기 때문이다 — 특히 거부된 접근은 본 트랜잭션 자체가 없다.
 */
@Service
@RequiredArgsConstructor
public class AdminAuditService {

    private final AdminAuditLogRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID operatorId, AdminAction action, AdminAuditLog.TargetType targetType,
                       UUID targetId, AdminAuditLog.Result result, String payload) {
        repository.save(AdminAuditLog.of(operatorId, action, targetType, targetId, result,
                digest(payload), Instant.now()));
    }

    // 아래 둘에도 전파 설정을 붙인다. 이 클래스 안에서 record 를 부르면 프록시를 거치지 않아
    // 애노테이션이 무시되고, 호출자가 readOnly 트랜잭션이면 저장이 flush 되지 않은 채 사라진다
    // — 조회 감사가 통째로 비는 형태로 나타난다.

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void allowed(UUID operatorId, AdminAction action, AdminAuditLog.TargetType targetType,
                        UUID targetId, String payload) {
        record(operatorId, action, targetType, targetId, AdminAuditLog.Result.ALLOWED, payload);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void denied(UUID operatorId, AdminAction action, String payload) {
        record(operatorId, action, null, null, AdminAuditLog.Result.DENIED, payload);
    }

    /** 본문을 통째로 남기지 않는다 — 제재 사유에는 신고 내용이 인용될 수 있다. */
    private String digest(String payload) {
        if (payload == null || payload.isBlank()) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("감사 로그 다이제스트 생성 실패", e);
        }
    }
}
