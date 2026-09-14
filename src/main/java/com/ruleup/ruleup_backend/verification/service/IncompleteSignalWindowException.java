package com.ruleup.ruleup_backend.verification.service;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 그 귀속일의 원본을 <b>전부 읽지 못했다</b>는 신호.
 *
 * <p>판정이 원본 전량 재평가로 바뀐 뒤, 일부만 읽고 내린 결론은 그냥 틀린 결론이다. 잘린 쪽에
 * 위반 신호가 있었다면 규칙 지키기형이 <b>잘못 성공</b>하고, 사용 구간이 잘렸다면 목표 달성형이
 * 실제보다 모자라 보인다. 어느 쪽이든 사용자에게 잘못된 결과가 확정된다.
 *
 * <p>그래서 「로그만 남기고 계속」이 아니라 <b>확정을 멈춘다.</b> 확정 배치에서는 이 예외가
 * 격리 경로로 흘러 그 건만 뒤로 밀리고, sync 에서는 그 날 평가를 건너뛴다 — 둘 다 판정을
 * 미룰 뿐 잘못된 값으로 굳히지 않는다.
 */
public class IncompleteSignalWindowException extends RuntimeException {

    public IncompleteSignalWindowException(UUID userId, LocalDate targetDate, String table, int ceiling) {
        super("원본을 전부 읽지 못했다 — 판정을 확정하지 않는다. userId=" + userId
                + " targetDate=" + targetDate + " table=" + table + " ceiling=" + ceiling);
    }
}
