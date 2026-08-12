package com.ruleup.ruleup_backend.challenge.counter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 동시 참여 한도 정책 — "한 사람이 동시에 몇 개의 챌린지를 진행할 수 있는가".
 *
 * <p><b>지금은 꺼져 있다(기본 {@code enabled=false}).</b> 테스트 기간에는 인원이 적어 한도가
 * 검증을 방해하기만 하므로 판정을 통과시킨다. 판정 로직·에러 계약·테스트는 전부 살아 있고
 * 스위치만 내려둔 상태다 — <b>정식 배포 전에 아래 설정을 켜면 그대로 동작한다.</b>
 *
 * <pre>
 * app:
 *   challenge:
 *     concurrent-limit:
 *       enabled: true    # ← 배포 전 이 줄만 바꾼다 (또는 env CHALLENGE_LIMIT_ENABLED=true)
 *       max: 3
 * </pre>
 *
 * <p>코드를 주석 처리하지 않고 설정 스위치로 둔 이유: 주석은 테스트가 돌지 않아 켜는 날 살아 있다는
 * 보장이 없고, 켜려면 코드 수정·리뷰·배포가 다시 필요하다. 스위치면 판정 경로가 CI 에서 계속 검증되고
 * (테스트 프로파일은 켜 둔다) 켜는 일은 설정 변경 한 줄로 끝난다.
 *
 * <p><b>한도는 가입과 생성 양쪽에 건다.</b> 방을 만들면 생성자가 그 방의 ACTIVE 멤버가 되어 슬롯을
 * 실제로 쓰기 때문이다. 생성만 열어두면 "방은 얼마든지 만들 수 있는데 남의 방에는 못 들어가는"
 * 비대칭이 생기고, 그 상태를 만든 게 서버라 사용자는 이유를 알 수 없다.
 */
@Component
public class ConcurrentChallengeLimitPolicy {

    private final boolean enabled;
    private final int max;

    public ConcurrentChallengeLimitPolicy(
            @Value("${app.challenge.concurrent-limit.enabled:false}") boolean enabled,
            @Value("${app.challenge.concurrent-limit.max:3}") int max) {
        this.enabled = enabled;
        this.max = max;
    }

    /** 지금 슬롯을 {@code currentSlots} 개 쓰고 있는 사람이 하나 더 시작하려 할 때 막아야 하는가. */
    public boolean exceeded(int currentSlots) {
        return enabled && currentSlots >= max;
    }

    /** 한도 자체가 켜져 있는가(안내 문구·미리보기 판정용). */
    public boolean enabled() {
        return enabled;
    }

    public int max() {
        return max;
    }
}
