package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.verification.config.VerificationProperties;
import com.ruleup.ruleup_backend.verification.domain.VerificationDeadlines;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * GPS 원본 좌표의 <b>건별 파기</b> (인증 공통 5-6 · 백엔드 정합화 1절).
 *
 * <h4>타이머는 「확정한 사람」이 아니라 「그 날짜」에 건다</h4>
 * 한 사용자의 위치 원본은 <b>여러 챌린지가 공유</b>한다(스펙: 사용자 신호 1회 저장). 그래서 타이머를
 * "이 판정이 확정된 시각 + 보관 기간"으로 잡으면, 아침에 즉시 성공한 챌린지 하나가 그날 좌표 전부에
 * 이른 파기 시각을 박아 버린다 — 같은 날짜의 다른 챌린지는 D+2 에 확정되는데 그때는 좌표가 없다.
 *
 * <p>그래서 기준을 <b>귀속일의 확정 경계</b>(D+2 00:00 KST)로 삼는다. 그 날짜의 어떤 판정도 그보다
 * 늦게 확정되지 않으므로, 하나의 시각이 그날 좌표를 쓰는 <b>모든</b> 챌린지에 대해 안전하다.
 * 계산이 날짜만으로 정해져 <b>적재 시점에 미리 박아 둘 수 있다</b> — 인증에 한 번도 쓰이지 않은
 * 좌표(위치 챌린지가 없는 사용자의 신호)까지 빠짐없이 파기 대상이 된다.
 *
 * <h4>그래도 확정 전에는 지우지 않는다</h4>
 * 확정 배치가 밀리면 경계가 지나도 판정이 안 끝나 있을 수 있다. 그래서 파기 직전에 <b>그 유저의
 * 그 날짜에 미확정 판정이 남아 있는지</b>를 한 번 더 본다. 스펙이 "확정 전 건이 지워지지 않는다"고
 * 적은 보장이 이 조건이다.
 *
 * <h4>행이 아니라 좌표만 지운다</h4>
 * 행까지 지우면 「이 신호를 받아 이렇게 판정했다」는 사실이 사라져 이의·분쟁에 답할 수 없다.
 * 좌표가 담긴 payload 만 파기 표식으로 덮고 {@code purgedAt} 을 남긴다.
 */
@Service
@RequiredArgsConstructor
public class LocationPurgeService {

    private static final Logger log = LoggerFactory.getLogger(LocationPurgeService.class);

    /** 한 문장으로 지울 행 수. 남은 건은 같은 실행 안에서 다음 문장이 가져간다. */
    private static final int PURGE_BATCH = 5_000;

    /** 한 번 깨워 비우는 데 쓸 시간 예산. 넘기면 다음 주기가 이어 간다. */
    private static final Duration DRAIN_BUDGET = Duration.ofMinutes(10);

    /** 파기 후 payload 자리에 남기는 표식. NOT NULL 이라 비울 수 없고, 비우면 스키마도 흐려진다. */
    private static final String PURGED_PAYLOAD = "{\"purged\":true}";

    private final JdbcTemplate jdbc;
    private final VerificationProperties properties;
    private final VerificationMetrics metrics;

    /**
     * 그 귀속일 좌표의 파기 예정 시각 — <b>날짜만으로 정해진다.</b>
     *
     * <p>{@code 확정 경계(D+2 00:00 KST) + 보관 기간}. 보관 기간 기본값이 0 이면 경계가 지나는
     * 즉시 파기 대상이 된다 — 위치정보법의 목적 달성 시 즉시 파기 원칙에 가장 가깝다.
     */
    public Instant purgeAfterFor(LocalDate targetDate) {
        return VerificationDeadlines.finalizeAfter(targetDate)
                .plus(Duration.ofDays(properties.gpsRetentionDays()));
    }

    /**
     * 확정된 판정이 소비한 좌표에 <b>추적용 판정 id</b> 를 남긴다.
     *
     * <p>파기 시각은 적재 때 이미 박혀 있으므로 여기서 앞당기거나 미루지 않는다 — 그렇게 하면
     * 먼저 확정한 챌린지가 나중 챌린지의 판정 근거를 지우는 예전 문제가 그대로 돌아온다.
     * 예전 적재분처럼 시각이 비어 있는 행만 날짜 기준으로 채운다.
     */
    @Transactional
    public void scheduleFor(UUID userId, LocalDate targetDate, UUID verificationId, Instant confirmedAt) {
        if (userId == null || targetDate == null) return;
        try {
            jdbc.update("UPDATE " + SignalDomain.LOCATION.table()
                            + " SET verificationId = COALESCE(verificationId, ?),"
                            + "     purgeAfter = COALESCE(purgeAfter, ?)"
                            + " WHERE observedDate = ? AND userId = ?",
                    (verificationId != null) ? bytes(verificationId) : null,
                    Timestamp.from(purgeAfterFor(targetDate)),
                    Date.valueOf(targetDate), bytes(userId));
        } catch (RuntimeException e) {
            // 판정은 이미 끝났다. 추적 id 한 줄 때문에 되돌리지 않는다.
            log.warn("좌표 파기 정보 갱신 실패 userId={} targetDate={} err={}",
                    userId, targetDate, e.toString());
        }
    }

    /**
     * 매일 03:05 KST — 파기 예정 시각이 지난 좌표를 지운다.
     *
     * <p>03:20 신호 파티션 정비보다 <b>먼저</b> 돈다. 파티션이 먼저 떨어지면 파기 사실을 남길
     * 행도 함께 사라져 「언제 지웠는가」를 증명할 수 없다.
     *
     * <p>대상이 남아 있는 동안 이어 돌린다. 한 번에 5,000행만 지우고 끝내면 목표 트래픽에서
     * 적체가 쌓이고, 밀린 행은 파기 기록 없이 파티션과 함께 사라진다.
     */
    @Scheduled(cron = "0 5 3 * * *", zone = "Asia/Seoul")
    public void purgeDue() {
        Instant deadline = Instant.now().plus(DRAIN_BUDGET);
        int total = 0;
        while (Instant.now().isBefore(deadline)) {
            int purged = purgeChunk();
            if (purged == 0) break;
            total += purged;
        }
        if (total > 0) {
            metrics.locationCoordinatesPurged(total);
            // 파기 사실의 기록은 이 로그가 맡는다(로그그룹 보관 90일). 행 자체는 파티션 수명이
            // 다하면 사라지므로, 「무엇을 언제 지웠는가」를 남길 자리가 여기다.
            log.info("[PRIVACY] gps_coordinates_purged count={} at={}", total, Instant.now());
        }
    }

    /**
     * 한 묶음 파기.
     *
     * <p><b>미확정 판정이 남은 유저·날짜는 건너뛴다.</b> 확정 배치가 밀려 경계가 지났는데도
     * 판정이 안 끝난 경우가 있고, 그때 좌표를 지우면 판정할 근거가 사라진다.
     */
    private int purgeChunk() {
        Instant now = Instant.now();
        try {
            return jdbc.update("UPDATE " + SignalDomain.LOCATION.table() + " s"
                            + " SET s.payload = ?, s.purgedAt = ?"
                            + " WHERE s.purgedAt IS NULL AND s.purgeAfter IS NOT NULL AND s.purgeAfter <= ?"
                            + "   AND NOT EXISTS ("
                            + "       SELECT 1 FROM VerificationDaily d"
                            + "        WHERE d.userId = s.userId AND d.targetDate = s.observedDate"
                            + "          AND d.status = 'PENDING')"
                            + " LIMIT " + PURGE_BATCH,
                    PURGED_PAYLOAD, Timestamp.from(now), Timestamp.from(now));
        } catch (RuntimeException e) {
            // 이 배치가 밀리면 위치정보법 위반이다 — 조용히 넘기지 않고 에러로 남긴다.
            log.error("GPS 원본 좌표 파기 실패 — 지연이 쌓이면 안 된다. err={}", e.toString(), e);
            return 0;
        }
    }

    private static byte[] bytes(UUID id) {
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(id.getMostSignificantBits());
        bb.putLong(id.getLeastSignificantBits());
        return bb.array();
    }
}
