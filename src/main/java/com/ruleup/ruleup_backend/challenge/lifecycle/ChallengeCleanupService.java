package com.ruleup.ruleup_backend.challenge.lifecycle;

import com.ruleup.ruleup_backend.common.image.ImageStorageService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 생성 부속 데이터 정리 배치 — 매일 03:40 KST.
 *  - 만료(24h) 초안: draftId 원본 대조는 24시간만 유효 — 지난 건 일괄 삭제.
 *  - 미등록 업로드 이미지: 업로드 후 24시간이 지나도록 챌린지에 등록되지 않은 소유 기록과
 *    <b>그 실제 파일</b>을 함께 삭제.
 *
 * <p>파일 삭제를 버킷 수명주기 규칙에 맡기지 않는 이유는 <b>대상을 구분할 수 없기 때문</b>이다.
 * 미등록 업로드와 실제 등록된 챌린지 이미지가 같은 접두사 아래 섞여 있어서, 나이만 보고 지우는
 * 규칙은 멀쩡한 방의 대표 이미지를 지운다. 반면 이 배치는 <b>어느 URL 이 고아인지 정확히 안다.</b>
 */
@Service
@RequiredArgsConstructor
public class ChallengeCleanupService {

    private static final Logger log = LoggerFactory.getLogger(ChallengeCleanupService.class);

    private final JdbcTemplate jdbc;
    private final ImageStorageService imageStorage;

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

    /**
     * 24시간 지나도록 챌린지에 등록되지 않은 업로드 이미지의 소유 기록과 파일을 정리한다.
     *
     * <p><b>URL 을 먼저 읽고 행을 지운 뒤 파일을 지운다.</b> 순서가 반대면 파일은 사라졌는데
     * 트랜잭션이 롤백돼 "행은 있는데 이미지가 없는" 상태가 남는다. 반대로 이 순서에서 파일
     * 삭제가 실패하면 객체만 남는데, 그건 아무도 참조하지 않는 쓰레기라 다음에 손으로 치우면 된다.
     */
    @Transactional
    public void cleanupOrphanImageUploads() {
        String orphanCondition = "registered_at IS NULL AND created_at < DATE_SUB(NOW(6), INTERVAL 24 HOUR)";
        List<String> urls = jdbc.queryForList(
                "SELECT image_url FROM challenge_image_uploads WHERE " + orphanCondition, String.class);

        int deleted = jdbc.update("DELETE FROM challenge_image_uploads WHERE " + orphanCondition);
        if (deleted == 0) return;

        urls.forEach(imageStorage::deleteByUrl);
        log.info("미등록 업로드 이미지 정리: {}건 삭제(파일 포함)", deleted);
    }
}
