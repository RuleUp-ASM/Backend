package com.ruleup.ruleup_backend.watcher.service;

import com.ruleup.ruleup_backend.common.outbox.OutboxHandler;
import com.ruleup.ruleup_backend.common.outbox.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 실패 확정 → 감시자 통지. <b>아웃박스로 받는다.</b>
 *
 * <p>예전에는 커밋 이후 인메모리 이벤트로 받고 실패하면 경고만 남겼다. 그러면 통지 한 번이
 * 실패하는 순간 <b>그 실패는 감시자에게 영원히 가지 않는다</b> — 재시도할 근거가 어디에도 없다.
 * 발행 의사를 확정과 같은 커밋에 적어 두면 스윕이 반드시 줍는다.
 *
 * <p>소비 시 WatcherNoticeService가 저장된 판정·기한·동의를 다시 검사한다.
 *
 * <p><b>예외를 삼키지 않는다.</b> 던져야 디스패처가 실패로 기록하고 백오프 뒤 다시 부른다.
 * 통지 적재는 {@code (relationId, verificationId)} 기준 멱등이라 두 번 불려도 한 번만 나간다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WatcherFailureOutboxHandler implements OutboxHandler {

    /** 아웃박스 라우팅 키. */
    public static final String OUTBOX_TYPE = "ROUTINE_FAILURE_CONFIRMED";

    private final WatcherNoticeService noticeService;

    /**
     * 발행 스냅샷. 시각·날짜를 문자열로 싣는다 — 확정 당시의 값을 그대로 보존하고,
     * 직렬화 형식이 payload 에 드러나 나중에 읽는 사람이 해석에 헤매지 않는다.
     */
    public record Payload(String challengeId, String userId, String verificationId,
                          String targetDate, String confirmedAt) {}

    @Override
    public String type() {
        return OUTBOX_TYPE;
    }

    @Override
    public void handle(String payload) {
        Payload event = OutboxService.parse(payload, Payload.class);
        noticeService.onFailureConfirmed(
                UUID.fromString(event.challengeId()),
                UUID.fromString(event.userId()),
                UUID.fromString(event.verificationId()),
                LocalDate.parse(event.targetDate()),
                Instant.parse(event.confirmedAt()));
    }
}
