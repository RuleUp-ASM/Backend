package com.ruleup.ruleup_backend.challenge.service;

import com.ruleup.ruleup_backend.common.outbox.OutboxHandler;
import com.ruleup.ruleup_backend.common.outbox.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 부정행위 검출 확정 → <b>해당 챌린지 강퇴·영구 차단</b> 집행 (공통 5-7). <b>아웃박스로 받는다.</b>
 *
 * <p>판정은 인증 모듈이 하고 여기는 집행만 한다. 강퇴 3종 중 유일하게 백오프가 아니라 영구
 * 차단이며, 필수(A) 통지도 그 경로가 함께 낸다.
 *
 * <p>예전에는 인메모리 이벤트로 받고 실패하면 경고만 남겼다. 게다가 검출 기록이 이미 있으면
 * 재호출이 이벤트를 다시 내지 않아, <b>최초 집행 실패가 영구 미집행으로 굳었다</b> — 기록에는
 * 부정행위가 남았는데 강퇴는 끝내 일어나지 않는 상태다. 발행 의사를 검출 기록과 같은 커밋에
 * 적어 두면 스윕이 반드시 줍는다.
 *
 * <p>점수 감점과 <b>다른 메시지</b>로 나눈 이유는 재시도를 독립시키기 위함이다. 한 핸들러가
 * 둘 다 하면 감점 실패 때마다 강퇴 경로가 함께 다시 돈다.
 *
 * <p>재시도로 두 번 불려도 안전하다 — 이미 영구 차단된 멤버는 초입에서 되돌아간다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CheatKickOutboxHandler implements OutboxHandler {

    /** 아웃박스 라우팅 키. */
    public static final String OUTBOX_TYPE = "CHEAT_DETECTION_KICK";

    private final com.ruleup.ruleup_backend.room.service.AutomaticKickService kicks;

    public record Payload(String userId, String challengeId, String detectionId) {}

    @Override
    public String type() {
        return OUTBOX_TYPE;
    }

    @Override
    public void handle(String payload) {
        Payload event = OutboxService.parse(payload, Payload.class);
        kicks.enforce(UUID.fromString(event.challengeId()), UUID.fromString(event.userId()),
                com.ruleup.ruleup_backend.room.service.AutomaticKickService.Reason.CHEAT_DETECTED,
                UUID.fromString(event.detectionId()), null, java.util.Map.of("detectionId", event.detectionId()));
        log.info("부정행위 강퇴 집행 userId={} challengeId={}", event.userId(), event.challengeId());
    }
}
