package com.ruleup.ruleup_backend.challenge.lifecycle;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 생성 부속 데이터 정리 배치 — 매일 03:40 KST.
 *  - 만료(24h) 초안: draftId 원본 대조는 24시간만 유효 — 지난 건 일괄 삭제.
 *  - 미등록 업로드 이미지: 업로드 후 24시간이 지나도록 챌린지에 등록되지 않은 소유 기록 삭제
 *    (외부 URL 주입 방지용 소유 대장의 고아 행 정리 — 파일 자체는 스토리지 수명주기로 관리).
 */
@Service
@RequiredArgsConstructor
public class ChallengeCleanupService {

    private static final Logger log = LoggerFactory.getLogger(ChallengeCleanupService.class);

    private final JdbcTemplate jdbc;

    @Scheduled(cron = "0 40 3 * * *", zone = "Asia/Seoul")
    public void runDaily() {
        cleanupExpiredDrafts();
        cleanupOrphanImageUploads();
    }

    /** 만료(24h) 초안 삭제. */
    @Transactional
    public void cleanupExpiredDrafts() {
        int deleted = jdbc.update("DELETE FROM challenge_drafts WHERE expires_at < NOW(6)");
        if (deleted > 0) log.info("만료 초안 정리: {}건 삭제", deleted);
    }

    /** 24시간 지나도록 챌린지에 등록되지 않은 업로드 이미지 소유 기록 정리. */
    @Transactional
    public void cleanupOrphanImageUploads() {
        int deleted = jdbc.update("DELETE FROM challenge_image_uploads " +
                "WHERE registered_at IS NULL AND created_at < DATE_SUB(NOW(6), INTERVAL 24 HOUR)");
        if (deleted > 0) log.info("미등록 업로드 이미지 정리: {}건 삭제", deleted);
    }
}
