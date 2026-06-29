package com.ruleup.ruleup_backend.watcher.domain;

/**
 * 감시자 상태 (CLAUDE.md §11.4).
 *  - INVITED   : 초대 발급, 아직 수락/동의 전.
 *  - CONSENTED : 수락(유저)/동의(비유저) 완료. 챌린지 미시작이면 여기서 대기.
 *  - ACTIVE    : 통지 발송 가능 상태(챌린지 진행 중). §5.9 — 발송은 ACTIVE에서만.
 *  - EXPIRED   : 초대 7일 미수락 만료.
 *  - REVOKED   : 생성자 해제 또는 본인 수신거부.
 */
public enum WatcherStatus {
    INVITED, CONSENTED, ACTIVE, EXPIRED, REVOKED;

    public boolean isActive() { return this == ACTIVE; }
}
