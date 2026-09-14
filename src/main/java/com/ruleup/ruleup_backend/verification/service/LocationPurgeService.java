package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.verification.config.VerificationProperties;
import com.ruleup.ruleup_backend.verification.signal.SignalDomain;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * GPS 원본 좌표의 <b>건별 파기</b> (인증 공통 5-6 · 백엔드 정합화 1절).
 *
 * <h4>왜 일괄 시각이 아닌가</h4>
 * 위치정보법은 목적 달성 시 즉시 파기를 요구한다. 그런데 「목적 달성」 시점은 사용자마다 다르다 —
 * 판정이 확정된 때다. 고정된 날짜로 일괄 삭제하면 <b>아직 확정되지 않은 건의 좌표까지 지워져</b>
 * 판정할 근거가 사라진다. 그래서 확정 시점에 {@code purgeAfter = 확정 시각 + 보관 기간}을
 * 행에 적고, 그 시각이 지난 행만 지운다.
 *
 * <h4>행을 지우지 않고 좌표만 지운다</h4>
 * 행 자체를 지우면 「이 신호를 받아 이렇게 판정했다」는 사실까지 사라져 이의·분쟁에 답할 수
 * 없다. 좌표가 담긴 payload 만 파기 표시로 덮고 {@code purgedAt} 을 남긴다 — 이후에는 판정
 * 결과와 요약만 남는다는 스펙 문구가 이 모양이다.
 *
 * <p>파티션 파기(기본 3일)가 대개 먼저 도달하므로 이 배치는 평소에 할 일이 없다. 그래도
 * 필요한 이유는 보관 기간이 이상탐지 윈도우에 맞춰 늘어날 수 있고(서버 설정값), 그 순간
 * 「확정 전 건은 건드리지 않는다」를 지킬 장치가 여기뿐이기 때문이다.
 */
@Service
@RequiredArgsConstructor
public class LocationPurgeService {

    private static final Logger log = LoggerFactory.getLogger(LocationPurgeService.class);

    /** 한 번에 파기할 행 상한. 남은 건은 다음 주기가 가져간다. */
    private static final int PURGE_BATCH = 5_000;

    /** 파기 후 payload 자리에 남기는 표식. NOT NULL 이라 비울 수 없고, 비우면 스키마도 흐려진다. */
    private static final String PURGED_PAYLOAD = "{\"purged\":true}";

    private final JdbcTemplate jdbc;
    private final VerificationProperties properties;

    /**
     * 확정된 판정이 소비한 그날 좌표에 파기 타이머를 건다.
     *
     * <p>한 좌표가 여러 챌린지 판정에 공유되므로 {@code verificationId} 는 <b>마지막으로 확정된
     * 판정</b>이 된다. 소유 관계가 아니라 기준 시각을 읽기 위한 참조이고, 타이머는 이미 걸린
     * 값을 <b>뒤로 미루지 않는다</b> — 같은 좌표를 쓰는 판정이 여럿이면 가장 이른 확정이 기준이다.
     */
    @Transactional
    public void scheduleFor(UUID userId, LocalDate targetDate, UUID verificationId, Instant confirmedAt) {
        if (userId == null || targetDate == null) return;
        Instant purgeAfter = confirmedAt.plus(java.time.Duration.ofDays(properties.gpsRetentionDays()));
        try {
            jdbc.update("UPDATE " + SignalDomain.LOCATION.table()
                            + " SET verificationId = COALESCE(verificationId, ?), purgeAfter = ?"
                            + " WHERE observedDate = ? AND userId = ? AND purgeAfter IS NULL",
                    (verificationId != null) ? bytes(verificationId) : null,
                    Timestamp.from(purgeAfter), Date.valueOf(targetDate), bytes(userId));
        } catch (RuntimeException e) {
            // 판정은 이미 끝났다. 타이머는 다음 확정이 다시 건다.
            log.warn("좌표 파기 타이머 설정 실패 userId={} targetDate={} err={}",
                    userId, targetDate, e.toString());
        }
    }

    /**
     * 매일 03:05 KST — 파기 예정 시각이 지난 좌표를 지운다.
     *
     * <p>03:20 신호 파티션 정비보다 <b>먼저</b> 돈다. 파티션이 먼저 떨어지면 파기 사실을 남길
     * 행도 함께 사라져, 「언제 지웠는가」를 증명할 수 없다.
     */
    @Scheduled(cron = "0 5 3 * * *", zone = "Asia/Seoul")
    @Transactional
    public void purgeDue() {
        Instant now = Instant.now();
        try {
            int purged = jdbc.update("UPDATE " + SignalDomain.LOCATION.table()
                            + " SET payload = ?, purgedAt = ?"
                            + " WHERE purgedAt IS NULL AND purgeAfter IS NOT NULL AND purgeAfter <= ?"
                            + " LIMIT " + PURGE_BATCH,
                    PURGED_PAYLOAD, Timestamp.from(now), Timestamp.from(now));
            if (purged > 0) log.info("GPS 원본 좌표 파기: {}건", purged);
        } catch (RuntimeException e) {
            // 이 배치가 밀리면 위치정보법 위반이다 — 조용히 넘기지 않고 에러로 남긴다.
            log.error("GPS 원본 좌표 파기 실패 — 지연이 쌓이면 안 된다. err={}", e.toString(), e);
        }
    }

    private static byte[] bytes(UUID id) {
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(id.getMostSignificantBits());
        bb.putLong(id.getLeastSignificantBits());
        return bb.array();
    }
}
