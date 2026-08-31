package com.ruleup.ruleup_backend.admin.service;

import com.ruleup.ruleup_backend.challenge.service.ChallengeMemberService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 계정 제재의 부수 효과 — 전 챌린지 자동 탈퇴.
 *
 * <p><b>강퇴로 처리하지 않는다.</b> 감점도 재참여 백오프도 붙지 않으며, 랭킹에서만 이탈자
 * 공통 규칙으로 빠진다. 제재 해제 후에는 사용자가 직접 다시 참여해야 하고 자동 재입장은 없다.
 *
 * <p>제재 트랜잭션 커밋 뒤에 실행되므로, 여기서 실패해도 제재 자체는 유지된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SanctionLeaveListener {

    private final ChallengeMemberService challengeMemberService;

    @EventListener
    public void onSanction(AdminSanctionService.SanctionLeaveRequested event) {
        try {
            // 탈퇴와 같은 경로를 쓴다 — 방 멤버십·랭킹·정원 카운터가 하나의 경로로 갱신되게 한다.
            challengeMemberService.leaveAllForWithdrawal(event.userId());
        } catch (Exception e) {
            log.warn("제재에 따른 자동 탈퇴 실패 userId={}: {}", event.userId(), e.toString());
        }
    }
}
