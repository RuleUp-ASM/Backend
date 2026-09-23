package com.ruleup.ruleup_backend.challenge.lifecycle;

import com.ruleup.ruleup_backend.challenge.repository.ChallengeDelegationRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 방장 위임 만료 배치 (§7-2 — 요청 생성 +7일 경과 시 PENDING→EXPIRED).
 * JPQL 벌크 업데이트라 멱등하며, 다중 인스턴스에서 동시에 돌아도 결과가 같다.
 */
@Service
@RequiredArgsConstructor
public class ChallengeDelegationExpiryService {

    private static final Logger log = LoggerFactory.getLogger(ChallengeDelegationExpiryService.class);

    private final ChallengeDelegationRepository delegationRepository;

    /** 1분마다: 만료 시각이 지난 PENDING 위임 요청을 EXPIRED 로 전환. */
    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void expireDueDelegations() {
        int expired = delegationRepository.expirePendingDueBefore(Instant.now());
        if (expired > 0) log.info("만료된 방장 위임 요청 {}건", expired);
    }
}
