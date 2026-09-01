package com.ruleup.ruleup_backend.admin.service;

import com.ruleup.ruleup_backend.challenge.service.ChallengeMemberService;
import com.ruleup.ruleup_backend.common.outbox.OutboxHandler;
import com.ruleup.ruleup_backend.common.outbox.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 계정 제재의 부수 효과 — 전 챌린지 자동 탈퇴. <b>아웃박스로 받는다.</b>
 *
 * <p>예전에는 {@code afterCommit} 콜백으로 받았다. 그러면 제재는 커밋됐는데 서버가 그 직후 죽는
 * 순간 <b>자동 탈퇴가 통째로 사라지고 재시작해도 주울 근거가 없다</b> — 제재는 걸려 있는데 방에는
 * 그대로 남아 있는 상태가 영구히 남는다. 발행 의사를 제재와 같은 커밋에 적어 두면 그 창이 닫힌다.
 *
 * <p><b>강퇴로 처리하지 않는다.</b> 감점도 재참여 백오프도 붙지 않으며, 랭킹에서만 이탈자
 * 공통 규칙으로 빠진다. 제재 해제 후에는 사용자가 직접 다시 참여해야 하고 자동 재입장은 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SanctionLeaveListener implements OutboxHandler {

    /** 아웃박스 라우팅 키. */
    public static final String OUTBOX_TYPE = "SANCTION_LEAVE";

    private final ChallengeMemberService challengeMemberService;

    /** 발행 스냅샷 — 제재 사유가 나중에 수정돼도 이 값으로 처리한다. */
    public record Payload(UUID userId, String reason) {}

    @Override
    public String type() {
        return OUTBOX_TYPE;
    }

    /**
     * 재시도로 두 번 불려도 안전하다 — 이미 나간 방은 대상이 아니므로 두 번째 호출은 0건이다.
     */
    @Override
    public void handle(String payload) {
        Payload event = OutboxService.parse(payload, Payload.class);
        // 탈퇴와 같은 경로를 쓴다 — 방 멤버십·랭킹·정원 카운터가 하나의 경로로 갱신되게 한다.
        int left = challengeMemberService.leaveAllForWithdrawal(event.userId());
        log.info("제재에 따른 자동 탈퇴 userId={} rooms={}", event.userId(), left);
    }
}
