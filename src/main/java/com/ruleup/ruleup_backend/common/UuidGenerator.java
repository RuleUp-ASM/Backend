package com.ruleup.ruleup_backend.common;

import com.github.f4b6a3.uuid.UuidCreator;
import java.util.UUID;

/**
 * 애플리케이션 전역에서 사용하는 ID 생성기.
 * 모든 엔티티의 PK는 UUID v7 (RFC 9562, 시간순 정렬 가능)을 사용한다.
 */
public final class UuidGenerator {
    private UuidGenerator() {}

    /** UUID v7 (시간순 정렬 가능) */
    public static UUID generate() {
        return UuidCreator.getTimeOrderedEpoch();
    }
}