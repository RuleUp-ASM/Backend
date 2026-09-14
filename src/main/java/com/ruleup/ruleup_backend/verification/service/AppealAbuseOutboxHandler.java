package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.common.outbox.OutboxHandler;
import com.ruleup.ruleup_backend.common.outbox.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 이의 남용 이상탐지 입력 적재 (공통 5-1 「남용은 이상탐지로만 통제」 · 백엔드 4-5).
 *
 * <p><b>아웃박스로 받는다.</b> 인메모리 비동기로는 세 가지를 못 한다.
 * <ul>
 *   <li><b>유실</b> — 프로세스가 내려가면 큐에 있던 집계가 사라진다. 다시 주울 근거가 없다.</li>
 *   <li><b>지연 관측</b> — 스펙이 「이의 이상탐지 처리 지연·실패」를 알람 대상으로 뒀는데,
 *       스레드풀 안에서는 얼마나 밀렸는지 밖에서 알 수 없다.</li>
 *   <li><b>적체 관측</b> — 같은 이유로 쌓인 양을 볼 수 없다.</li>
 * </ul>
 * 아웃박스에 실으면 {@code outbox.pending.*} · {@code outbox.dead_lettered.*} 게이지가 그 셋을
 * 그대로 답해 준다 — 따로 배관을 만들 이유가 없다.
 *
 * <p>인용 자체는 이미 사용자에게 응답됐다. 여기서 예외를 던지면 아웃박스가 재시도하지만,
 * <b>인용을 되돌리지는 않는다</b> — 스펙이 "이상탐지 결과는 개별 인용을 지연하거나 뒤집지
 * 않는다"고 못 박았다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AppealAbuseOutboxHandler implements OutboxHandler {

    /** 아웃박스 라우팅 키. */
    public static final String OUTBOX_TYPE = "APPEAL_ABUSE_SAMPLE";

    private final AppealAbuseMonitor monitor;

    public record Payload(String appealId, String userId, String challengeId,
                          String verificationId, String targetDate, String acceptedAt) {}

    @Override
    public String type() {
        return OUTBOX_TYPE;
    }

    @Override
    public void handle(String payload) {
        Payload event = OutboxService.parse(payload, Payload.class);
        monitor.sample(
                UUID.fromString(event.appealId()),
                UUID.fromString(event.userId()),
                UUID.fromString(event.challengeId()),
                LocalDate.parse(event.targetDate()),
                Instant.parse(event.acceptedAt()));
    }
}
