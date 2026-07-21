package com.ruleup.ruleup_backend.user.domain;

import com.ruleup.ruleup_backend.common.UuidGenerator;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * tempNickname()은 PK(UUID v7)에서 결정적으로 파생하는 임시 표시명(저장 안 함).
 * 핵심: v7의 앞 구간은 타임스탬프라 같은 시간대 가입자끼리 겹치므로 <b>뒤 6자리</b>(랜덤 구간)를 써야 한다.
 */
class UserTempNicknameTest {

    /** id만 지정된 User를 만든다(create()는 id를 랜덤 생성하므로 파생 로직 검증용으로 직접 주입). */
    private static User userWithId(UUID id) {
        try {
            User u = new User();
            Field f = User.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(u, id);
            return u;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void format_isUserPlusLast6Hex() {
        UUID id = UUID.fromString("0190abcd-1234-7abc-8def-0000001a2b3c");
        assertThat(userWithId(id).tempNickname()).isEqualTo("user_1a2b3c");   // 하이픈 제거 후 뒤 6자리
    }

    @Test
    void deterministic_sameIdSameValue() {
        UUID id = UuidGenerator.generate();
        User u = userWithId(id);
        assertThat(u.tempNickname()).isEqualTo(u.tempNickname());              // 항상 동일
        assertThat(userWithId(id).tempNickname()).isEqualTo(u.tempNickname()); // 같은 id → 같은 값
    }

    /**
     * 타임스탬프 구간이 동일한(가입 시각이 가까운) 서로 다른 v7 UUID 두 개는
     * 서로 다른 tempNickname을 가져야 한다. (구버그: 앞 6자리를 쓰면 둘이 충돌했다.)
     */
    @Test
    void closeTimestamps_produceDifferentTempNicknames() {
        // 앞부분(타임스탬프·variant)은 동일, 랜덤 꼬리만 다른 두 UUID.
        UUID a = UUID.fromString("0190abcd-1234-7abc-8def-000000aaaaaa");
        UUID b = UUID.fromString("0190abcd-1234-7abc-8def-000000bbbbbb");

        String hexA = a.toString().replace("-", "");
        String hexB = b.toString().replace("-", "");
        assertThat(hexA.substring(0, 6)).isEqualTo(hexB.substring(0, 6));      // 앞 6자리는 같음(구버그가 충돌하던 지점)

        assertThat(userWithId(a).tempNickname())
                .isNotEqualTo(userWithId(b).tempNickname());                   // 새 로직(뒤 6자리)은 구분됨
    }

    /** 실제 v7 생성기로도 연속 생성 시(같은 시간대) 서로 다른 tempNickname이 나오는지 확인. */
    @Test
    void consecutiveV7Ids_areDistinct() {
        User u1 = userWithId(UuidGenerator.generate());
        User u2 = userWithId(UuidGenerator.generate());
        assertThat(u1.tempNickname()).isNotEqualTo(u2.tempNickname());
    }
}
