package com.ruleup.ruleup_backend.challenge.lifecycle;

import org.springframework.stereotype.Service;

/**
 * 생성 부속 데이터 정리 배치(만료 초안·미등록 이미지) — 구현은 라이프사이클 스텝에서 채운다.
 */
@Service
public class ChallengeCleanupService {

    /** 만료(24h) 초안 삭제. */
    public void cleanupExpiredDrafts() {
        // 선작성(레드) 스켈레톤
    }

    /** 24시간 지나도록 챌린지에 등록되지 않은 업로드 이미지 정리. */
    public void cleanupOrphanImageUploads() {
        // 선작성(레드) 스켈레톤
    }
}
