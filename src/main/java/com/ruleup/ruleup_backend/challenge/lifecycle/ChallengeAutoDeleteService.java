package com.ruleup.ruleup_backend.challenge.lifecycle;

import org.springframework.stereotype.Service;

/**
 * 챌린지 자동 삭제 배치(기간 만료·유령방) — 구현은 라이프사이클 스텝에서 채운다.
 */
@Service
public class ChallengeAutoDeleteService {

    /** 만료(COMPLETED)·유령방(ACTIVE 멤버 0명)을 이력 스냅샷 적재 후 하드 삭제한다. */
    public void runOnce() {
        // 선작성(레드) 스켈레톤
    }
}
