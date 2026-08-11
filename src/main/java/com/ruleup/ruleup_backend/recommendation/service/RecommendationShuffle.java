package com.ruleup.ruleup_backend.recommendation.service;

import java.util.UUID;

/**
 * 동점 후보의 정렬 키 — <b>사용자마다 다르게, 같은 사용자에겐 항상 같게</b>.
 *
 * <p>추천 점수는 동점이 대량으로 난다. 관심사 보너스가 평평한 가산점이라 한 카테고리 안의 템플릿이
 * 전부 같은 점수를 받기 때문이다. 이때 {@code templateId} 오름차순으로 동점을 깨면 결과가 이렇게 된다:
 * <ul>
 *   <li>모든 사용자에게 <b>늘 같은 3건</b>이 나간다 — 추천 사유가 {@code EXPLORE}(탐색)여도 탐색이 되지 않는다.</li>
 *   <li>id 가 큰 시드는 <b>영원히 노출되지 않는다</b>. 시드가 판정 모델별 id 블록으로 묶여 있어
 *       앞 블록이 뒤 블록을 계속 가린다.</li>
 * </ul>
 *
 * <p>그래서 id 대신 (userId, templateId) 를 섞은 값으로 정렬한다. 무작위가 아니라 <b>결정적</b>이라
 * 같은 사용자가 새로고침해도 순서가 흔들리지 않고(신뢰), 사용자마다는 다른 루틴이 뜬다(노출 분산).
 * 서버를 재시작해도 값이 같아야 하므로 해시 소스는 UUID 비트와 id 뿐이다.
 */
public final class RecommendationShuffle {

    private RecommendationShuffle() {}

    /** 값 자체에는 의미가 없다 — 오름차순 정렬용 키로만 쓴다. */
    public static long orderKey(UUID userId, long templateId) {
        long seed = (userId != null)
                ? userId.getMostSignificantBits() * 31L + userId.getLeastSignificantBits()
                : 0L;
        return mix(seed ^ (templateId * 0x9E3779B97F4A7C15L));
    }

    /** SplitMix64 finalizer — 인접한 id 가 인접한 키로 남지 않게 비트를 흩는다. */
    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
