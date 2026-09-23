package com.ruleup.ruleup_backend.challenge.explore;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 카테고리 그리드 캐시 무효화 수신자.
 *
 * <p>커밋 이후에만 버린다. 커밋 전에 버리면 그 사이 들어온 조회가 아직 반영 안 된 값을
 * 다시 캐시에 채워 넣어(= 되살아난 스테일) 무효화가 무의미해진다.
 * 실패는 삼킨다 — 표시용 수치 때문에 상태 전환 배치를 실패시킬 이유가 없고, TTL 이 자가치유한다.
 */
@Component
@RequiredArgsConstructor
public class CategoryCountEvictor {

    private static final Logger log = LoggerFactory.getLogger(CategoryCountEvictor.class);

    private final CategoryCountService categoryCountService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onGridChanged(ChallengeGridChanged event) {
        try {
            categoryCountService.evict();
        } catch (Exception e) {
            log.warn("카테고리 그리드 캐시 무효화 실패 reason={}: {}", event.reason(), e.getMessage());
        }
    }
}
