package com.ruleup.ruleup_backend.watcher.domain;

/**
 * 초대 토큰 상태.
 *  - INVITED   : 발급, 미수락.
 *  - CONSENTED : 수락/동의 완료(감시자 생성됨).
 *  - EXPIRED   : 7일 만료.
 *  - REVOKED   : 해제/수신거부로 무효.
 */
public enum InvitationStatus {
    INVITED, CONSENTED, EXPIRED, REVOKED;

    /** 살아있는 초대(정원 카운트 대상): 발급됐고 아직 유효. */
    public boolean countsTowardLimit() {
        return this == INVITED || this == CONSENTED;
    }
}
