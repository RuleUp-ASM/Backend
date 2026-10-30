package com.ruleup.ruleup_backend.challenge.lifecycle;

import com.ruleup.ruleup_backend.common.image.ImageStorageService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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

    /** 미등록 업로드 정리 대상 — 업로드 후 24시간 넘게 어느 챌린지에도 등록되지 않은 것. */
    private static final String ORPHAN_CONDITION =
            "registered_at IS NULL AND created_at < DATE_SUB(NOW(6), INTERVAL 24 HOUR)";

    private final JdbcTemplate jdbc;
    private final ImageStorageService imageStorage;
    private final TransactionTemplate tx;

    @SchedulerLock(name = "ChallengeCleanupService.runDaily", lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
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
     * <p><b>한 건씩, 행을 잡은 채로 파일을 먼저 지우고 행을 지운다.</b>
     * <ul>
     *   <li>후보 목록은 잠그지 않고 읽는다. 그 사이 등록될 수 있으므로 각 건은 행을 잠그며
     *       조건을 다시 확인하고, 그때도 미등록인 것만 지운다. 등록 경로도 같은 행을 잠그고
     *       읽기 때문에({@code findByImageUrlForUpdate}) 둘이 한 줄로 선다 — 목록을 읽은 뒤
     *       등록된 이미지의 파일까지 지우던 경합이 여기서 막힌다(#381).</li>
     *   <li>파일 삭제가 실패하면 그 건의 트랜잭션만 되돌려 <b>행을 남긴다.</b> 행이 곧 재시도
     *       대상 기록이라 다음 회차에 다시 선택된다. 예전처럼 행을 먼저 지우면 실패한 파일은
     *       아무도 모르는 쓰레기로 남았다(#382). 저장소 삭제는 없는 파일에도 성공하므로
     *       파일은 지워졌는데 행 삭제가 실패해도 다음 회차에 같은 일을 반복할 뿐이다.</li>
     * </ul>
     *
     * <p>파일 삭제를 트랜잭션 안에서 부르지만 잡는 것은 <b>그 한 행</b>뿐이고, 기다릴 수 있는
     * 상대는 같은 이미지를 등록하려는 요청뿐이다. 24시간 넘게 방치된 업로드라 실제로 겹칠 일은
     * 드물고, 겹쳐도 S3 호출 시간 제한(15초) 안에서 끝난다.
     */
    public void cleanupOrphanImageUploads() {
        List<String> candidates = jdbc.queryForList(
                "SELECT image_url FROM challenge_image_uploads WHERE " + ORPHAN_CONDITION + " ORDER BY id", String.class);

        int deleted = 0;
        int failed = 0;
        for (String url : candidates) {
            try {
                if (Boolean.TRUE.equals(tx.execute(status -> purgeOrphanUpload(url)))) deleted++;
            } catch (RuntimeException e) {
                // 한 건 실패로 뒤에 밀린 건까지 멈추지 않는다. 행은 남아 다음 회차에 다시 시도된다.
                failed++;
                log.warn("미등록 업로드 이미지 정리 실패 url={}: {}", url, e.toString());
            }
        }
        if (deleted > 0 || failed > 0)
            log.info("미등록 업로드 이미지 정리: {}건 삭제(파일 포함), {}건 실패(다음 회차 재시도)", deleted, failed);
    }

    /**
     * 행을 잠그고 아직 미등록이면 파일 → 행 순으로 지운다. 그사이 등록됐거나 사라졌으면 false.
     *
     * <p>잠금은 등록 경로와 <b>같은 길(image_url 유니크 인덱스)</b>로 잡는다. id 로 잡으면 등록 쪽이
     * 유니크 인덱스 항목을 쥔 채 행을 기다리고, 이쪽은 행을 쥔 채 그 인덱스 항목을 지우려 해
     * 교착이 난다(테스트로 재현됨).
     */
    private boolean purgeOrphanUpload(String url) {
        List<Long> id = jdbc.queryForList(
                "SELECT id FROM challenge_image_uploads WHERE image_url = ? AND " + ORPHAN_CONDITION + " FOR UPDATE",
                Long.class, url);
        if (id.isEmpty()) return false;
        imageStorage.deleteByUrl(url);                // 실패하면 예외 → 이 건만 롤백, 행 유지
        jdbc.update("DELETE FROM challenge_image_uploads WHERE id = ?", id.getFirst());
        return true;
    }
}
