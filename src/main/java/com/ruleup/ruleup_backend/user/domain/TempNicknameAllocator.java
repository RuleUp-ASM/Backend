package com.ruleup.ruleup_backend.user.domain;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.function.Predicate;

/**
 * 임시 승인 닉네임 할당기 — DB 정리 문서 §6.
 *
 * <p>8 hex 는 유한 공간이라 다른 사용자의 임시/승인 닉네임과 충돌할 수 있고, 사용자가 직접 고른
 * 닉네임이 8자리 hex 문자열일 수도 있다. 그래서 두 겹으로 막는다.
 * <ol>
 *   <li><b>사전 검사</b> — {@link #assign}: 후보가 점유돼 있으면 다른 값으로 재시도</li>
 *   <li><b>INSERT 충돌</b> — 사전 검사와 INSERT 사이의 경합(TOCTOU)은 DB
 *       {@code uq_users_active_approved_nickname} 이 잡는다. 호출측(가입)이 트랜잭션을 다시 열고
 *       {@link #reassign} 으로 새 후보를 받아 재시도한다.</li>
 * </ol>
 * INSERT 후 제약 위반을 같은 트랜잭션에서 복구할 수 없어(영속성 컨텍스트가 깨진다) 이렇게 나눴다.
 */
@Component
@RequiredArgsConstructor
public class TempNicknameAllocator {

    /** 사전 검사 재시도 상한 — 8 hex(약 43억) 공간에서 연속 충돌은 사실상 불가능하므로 넉넉하다. */
    public static final int MAX_TRIES = 5;

    private final TempNicknameGenerator generator;

    /**
     * 사용 가능한 임시 승인 닉네임을 user 에 할당한다.
     *
     * @param taken 이미 점유된 닉네임인지 판정(보통 {@code UserRepository::isNicknameTaken} 바인딩)
     * @throws IllegalStateException 재시도 상한까지 전부 충돌한 경우(→ 500, 관측되면 조사 대상)
     */
    public void assign(User user, Predicate<String> taken) {
        for (int attempt = 0; attempt < MAX_TRIES; attempt++) {
            String candidate = generator.next();
            if (!taken.test(candidate)) {
                user.assignApprovedNickname(candidate);
                return;
            }
        }
        throw new IllegalStateException(
                "임시 닉네임 생성이 " + MAX_TRIES + "회 연속 충돌했습니다. userId=" + user.getId());
    }

    /** INSERT 충돌 후 재시도용 — 사전 검사 없이 새 후보로 교체한다(다음 INSERT가 다시 검증). */
    public void reassign(User user) {
        user.assignApprovedNickname(generator.next());
    }
}
