package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.verification.config.VerificationProperties;
import com.ruleup.ruleup_backend.verification.signal.SignalDomain;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 원본 신호 파티션 유지 — <b>미래 파티션 확보 + 만료 파티션 파기</b>(백엔드 4-1-1).
 *
 * <h4>왜 DROP 인가</h4>
 * 판정 원본은 하루 수천만 건까지 갈 수 있는 hot storage 다. 만료분을 행 단위로 지우면 삭제
 * 자체가 부하가 되고 테이블이 비지 않는다. 일자 파티션을 통째로 떨어뜨리면 <b>상수 시간</b>이다.
 *
 * <h4>왜 미리 만드는가</h4>
 * 파티션이 없으면 적재가 {@code pFuture}(MAXVALUE) 로 몰리고, 그 덩어리는 날짜별로 떨어뜨릴 수
 * 없어 파기가 막힌다. 스펙이 최소 7일 앞을 요구하는 이유다 — 잡이 하루 이틀 밀려도 버틴다.
 *
 * <h4>기동 시에도 한 번 돈다</h4>
 * 마이그레이션 SQL 은 실행 시점을 몰라 날짜를 박아 둘 수 없다. 새로 만든 DB 는 {@code pFuture}
 * 하나로 시작하므로, 첫 적재 전에 일자 파티션을 잘라 두어야 한다.
 *
 * <p>DDL 이라 트랜잭션이 없다. 멀티 태스크가 동시에 돌면 한쪽이 「이미 있다·이미 없다」로
 * 실패하는데, 그건 <b>다른 쪽이 해냈다는 뜻</b>이라 경고만 남기고 넘어간다.
 */
@Component
@RequiredArgsConstructor
public class SignalPartitionMaintainer {

    private static final Logger log = LoggerFactory.getLogger(SignalPartitionMaintainer.class);

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 파티션 이름 — {@code p20260914}. 날짜를 이름에 담아야 만료 판정이 이름만으로 된다. */
    private static final DateTimeFormatter PARTITION_SUFFIX = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 상한이 열린 꼬리 파티션. 여기서 일자 파티션을 잘라낸다. */
    private static final String FUTURE_PARTITION = "pFuture";

    private final JdbcTemplate jdbc;
    private final VerificationProperties properties;

    /**
     * 매일 03:20 KST. 03:10 알림 파기와 03:30 탐색 reconciliation 사이다 —
     * 00시 확정 배치가 D-2 귀속 건을 끝낸 뒤라야 그 날짜 파티션을 떨어뜨릴 수 있다.
     */
    @Scheduled(cron = "0 20 3 * * *", zone = "Asia/Seoul")
    public void maintain() {
        LocalDate today = LocalDate.now(KST);
        for (SignalDomain domain : SignalDomain.values()) {
            addFuturePartitions(domain, today);
            dropExpiredPartitions(domain, today);
        }
    }

    /** 기동 직후 한 번 — 새 DB 는 {@code pFuture} 하나뿐이라 첫 적재 전에 잘라 두어야 한다. */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            maintain();
        } catch (RuntimeException e) {
            // 파티션을 못 만들어도 적재는 pFuture 로 들어간다. 기동을 막을 일은 아니다.
            log.warn("신호 파티션 초기 정비 실패 — 적재는 계속된다. err={}", e.toString());
        }
    }

    /**
     * 오늘부터 lookahead 일까지의 일자 파티션을 확보한다.
     *
     * <p>MySQL 은 MAXVALUE 파티션 뒤에 새 파티션을 붙일 수 없어 <b>REORGANIZE</b> 로 잘라낸다.
     * {@code pFuture} 가 비어 있는 한(= 앞서 파티션을 확보해 둔 한) 이 작업은 데이터 복사가 없다.
     */
    private void addFuturePartitions(SignalDomain domain, LocalDate today) {
        Set<String> existing = existingPartitions(domain);
        List<LocalDate> missing = new ArrayList<>();
        for (int i = 0; i <= properties.signalPartitionLookaheadDays(); i++) {
            LocalDate date = today.plusDays(i);
            if (!existing.contains(partitionName(date))) missing.add(date);
        }
        if (missing.isEmpty()) return;

        StringBuilder sql = new StringBuilder("ALTER TABLE ").append(domain.table())
                .append(" REORGANIZE PARTITION ").append(FUTURE_PARTITION).append(" INTO (");
        for (LocalDate date : missing) {
            sql.append("PARTITION ").append(partitionName(date))
                    .append(" VALUES LESS THAN (TO_DAYS('").append(date.plusDays(1)).append("')), ");
        }
        sql.append("PARTITION ").append(FUTURE_PARTITION).append(" VALUES LESS THAN MAXVALUE)");

        try {
            jdbc.execute(sql.toString());
            log.info("신호 파티션 확보 table={} added={}", domain.table(), missing.size());
        } catch (RuntimeException e) {
            log.warn("신호 파티션 확보 실패 table={} err={}", domain.table(), e.toString());
        }
    }

    /**
     * 보관 기간이 지난 일자 파티션을 떨어뜨린다.
     *
     * <p>D일 신호는 D+2 00:00 KST 확정이 끝나면 판정 원본으로서의 목적이 끝난다. 기본값은
     * 오늘·D-1·D-2 를 남기는 3일이다 — 확정이 방금 끝난 날짜까지 하루 더 붙잡고 있는 셈이라
     * 배치가 밀려도 원본이 먼저 사라지지 않는다.
     */
    private void dropExpiredPartitions(SignalDomain domain, LocalDate today) {
        LocalDate oldestKept = today.minusDays(properties.signalRetentionDays());
        for (String name : existingPartitions(domain)) {
            LocalDate date = dateOf(name);
            if (date == null || !date.isBefore(oldestKept)) continue;
            try {
                jdbc.execute("ALTER TABLE " + domain.table() + " DROP PARTITION " + name);
                log.info("신호 파티션 파기 table={} partition={}", domain.table(), name);
            } catch (RuntimeException e) {
                log.warn("신호 파티션 파기 실패 table={} partition={} err={}",
                        domain.table(), name, e.toString());
            }
        }
    }

    private Set<String> existingPartitions(SignalDomain domain) {
        List<String> names = jdbc.queryForList(
                "SELECT PARTITION_NAME FROM information_schema.PARTITIONS"
                        + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?"
                        + " AND PARTITION_NAME IS NOT NULL",
                String.class, domain.table());
        return new LinkedHashSet<>(names);
    }

    private static String partitionName(LocalDate date) {
        return "p" + date.format(PARTITION_SUFFIX);
    }

    /** {@code p20260914} → 날짜. 꼬리 파티션처럼 날짜가 아닌 이름은 null 이다. */
    private static LocalDate dateOf(String partitionName) {
        if (partitionName == null || partitionName.length() != 9 || partitionName.charAt(0) != 'p') {
            return null;
        }
        try {
            return LocalDate.parse(partitionName.substring(1), PARTITION_SUFFIX);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
