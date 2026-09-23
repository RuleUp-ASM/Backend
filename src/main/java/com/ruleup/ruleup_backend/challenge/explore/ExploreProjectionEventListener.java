package com.ruleup.ruleup_backend.challenge.explore;

import com.ruleup.ruleup_backend.challenge.explore.store.ExploreIndexer;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 설정이 바뀐 방의 탐색 투영을 커밋 직후 다시 만든다.
 *
 * <p>커밋 <b>이후</b>여야 한다. 트랜잭션 안에서 Redis 를 건드리면 뒤이어 롤백됐을 때 파생만
 * 새 값으로 남아, 바뀌지 않은 방이 바뀐 것처럼 보인다.
 *
 * <p>실패는 삼킨다 — 파생값이라 원래 요청을 되돌릴 이유가 없고, 늦어도 5분 보정이 따라잡는다.
 * 내부 회로차단기가 Redis 장애를 감싸므로 여기서 터지지도 않는다.
 */
@Component
@RequiredArgsConstructor
public class ExploreProjectionEventListener {

    private static final Logger log = LoggerFactory.getLogger(ExploreProjectionEventListener.class);

    private final ExploreIndexer indexer;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProjectionRequested(ChallengeExploreProjectionRequested event) {
        try {
            indexer.index(event.challengeId());
        } catch (Exception e) {
            log.warn("탐색 투영 갱신 실패 challengeId={} — 5분 보정이 따라잡는다: {}",
                    event.challengeId(), e.toString());
        }
    }
}
