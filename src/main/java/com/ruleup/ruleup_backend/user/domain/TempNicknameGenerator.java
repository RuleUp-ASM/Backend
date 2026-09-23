package com.ruleup.ruleup_backend.user.domain;

/**
 * 임시 승인 닉네임(8 hex) 후보 생성기 — DB 정리 문서 §6.
 *
 * <p>가입 직후 {@code approved_nickname}(타인에게 노출되는 값)으로 쓰인다. 충돌하면 다시 호출해
 * 다른 값을 받는다. 인터페이스로 분리한 이유는 <b>테스트에서 충돌 상황을 재현</b>하기 위해서다 —
 * 값이 난수라 후보를 고정하지 않으면 UNIQUE 충돌 경로를 태울 수 없다.
 */
@FunctionalInterface
public interface TempNicknameGenerator {

    /** 새 후보 8자리. {@code approved_nickname VARCHAR(12)} 안에 들어가야 해서 접두사는 붙이지 않는다. */
    String next();
}
