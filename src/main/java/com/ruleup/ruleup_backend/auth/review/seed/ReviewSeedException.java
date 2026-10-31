package com.ruleup.ruleup_backend.auth.review.seed;

/** 시드가 아무것도 바꾸지 않고 멈춘 이유. 메시지에는 round·UUID·건수만 싣는다 — 코드·해시는 없다(5-7). */
public class ReviewSeedException extends RuntimeException {

    public enum Reason {
        /** 그날 순번 99 를 다 썼다. */
        ROUND_EXHAUSTED,
        /** 계획한 계정 UUID 의 행이 이미 있다. */
        ACCOUNT_EXISTS,
        /** 그 round 의 subject 가 이미 있다 — 같은 round 로 두 번 실행됐다. */
        ROUND_TAKEN,
        /** 확인 인자의 round 가 설정의 round 와 다르다. */
        ROUND_MISMATCH,
        /** 설정의 스위치가 꺼져 있다(enabled=false 또는 만료). */
        SWITCHED_OFF,
        /** codes 에 이번 회차 계정이 아닌 항목이 있다. */
        MIXED_ROUND,
        /** codes 의 계정 행이 하나도 없다. */
        NO_TARGET
    }

    private final Reason reason;

    public ReviewSeedException(Reason reason, String message) {
        super(reason + ": " + message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
