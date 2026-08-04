package com.ruleup.ruleup_backend.user.domain;

import java.util.function.Predicate;

/**
 * 임시 승인 닉네임(UUID 뒤 8자리) 할당기 — DB 정리 문서 §6.
 *
 * <p>가입 직후 {@code approved_nickname} 은 UUID 파생 8자리인데, 8 hex 는 유한 공간이라
 * 다른 사용자의 임시/승인 닉네임과 충돌할 수 있다. 문서 요구대로 <b>충돌하면 다른 8자리를 생성해
 * 재시도</b>한다(INSERT 후 UNIQUE 위반을 잡는 대신, 영속성 컨텍스트가 깨지지 않도록 사전 검사로 처리).
 * DB의 {@code uq_users_active_approved_nickname} 은 최후 방어선으로 남는다.
 */
public final class TempNicknameAllocator {

    /** 재시도 상한 — 8 hex(약 43억) 공간에서 연속 충돌은 사실상 불가능하므로 넉넉하다. */
    public static final int MAX_TRIES = 5;

    private TempNicknameAllocator() {}

    /**
     * 사용 가능한 임시 승인 닉네임을 user 에 할당한다.
     *
     * @param taken 이미 점유된 닉네임인지 판정(보통 UserRepository::isNicknameTaken 바인딩)
     * @throws IllegalStateException 재시도 상한까지 전부 충돌한 경우(→ 500, 관측되면 조사 대상)
     */
    public static void assign(User user, Predicate<String> taken) {
        for (int attempt = 0; attempt < MAX_TRIES; attempt++) {
            if (!taken.test(user.getApprovedNickname())) return;
            user.regenerateTempApprovedNickname();
        }
        throw new IllegalStateException(
                "임시 닉네임 생성이 " + MAX_TRIES + "회 연속 충돌했습니다. userId=" + user.getId());
    }
}
