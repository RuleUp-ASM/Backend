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
            // 판정 원본 — 현재 귀속일과 직전 유예 귀속일만 필요한 hot storage.
            addFuturePartitions(domain.table(), today);
            dropExpiredPartitions(domain, today, properties.signalRetentionDays());
            // 이상탐지 입력 — 스펙이 못 박은 최대 30일. 같은 파티션 전략으로 걷는다.
            addFuturePartitions(domain.anomalyTable(), today);
            dropAnomalyPartitions(domain.anomalyTable(), today, properties.anomalyRetentionDays());
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
    private void addFuturePartitions(String table, LocalDate today) {
        Set<String> existing = existingPartitions(table);
        List<LocalDate> missing = new ArrayList<>();
        for (int i = 0; i <= properties.signalPartitionLookaheadDays(); i++) {
            LocalDate date = today.plusDays(i);
            if (!existing.contains(partitionName(date))) missing.add(date);
        }
        if (missing.isEmpty()) return;

        StringBuilder sql = new StringBuilder("ALTER TABLE ").append(table)
                .append(" REORGANIZE PARTITION ").append(FUTURE_PARTITION).append(" INTO (");
        for (LocalDate date : missing) {
            sql.append("PARTITION ").append(partitionName(date))
                    .append(" VALUES LESS THAN (TO_DAYS('").append(date.plusDays(1)).append("')), ");
        }
        sql.append("PARTITION ").append(FUTURE_PARTITION).append(" VALUES LESS THAN MAXVALUE)");

        try {
            jdbc.execute(sql.toString());
            log.info("신호 파티션 확보 table={} added={}", table, missing.size());
        } catch (RuntimeException e) {
            log.warn("신호 파티션 확보 실패 table={} err={}", table, e.toString());
        }
    }

    /**
     * 보관 기간이 지난 일자 파티션을 떨어뜨린다.
     *
     * <p>D일 신호는 D+2 00:00 KST 확정이 끝나면 판정 원본으로서의 목적이 끝난다. 기본값은
     * 오늘·D-1·D-2 를 남기는 3일이다 — 확정이 방금 끝난 날짜까지 하루 더 붙잡고 있는 셈이라
     * 배치가 밀려도 원본이 먼저 사라지지 않는다.
     */
    private void dropExpiredPartitions(SignalDomain domain, LocalDate today, int retentionDays) {
        LocalDate oldestKept = oldestKept(today, retentionDays);
        for (String name : existingPartitions(domain.table())) {
            LocalDate date = dateOf(name);
            if (date == null || !date.isBefore(oldestKept)) continue;
            if (domain == SignalDomain.LOCATION && holdForUnconfirmed(date, today, retentionDays)) continue;
            dropPartition(domain.table(), name);
        }
    }

    private void dropAnomalyPartitions(String table, LocalDate today, int retentionDays) {
        LocalDate oldestKept = oldestKept(today, retentionDays);
        for (String name : existingPartitions(table)) {
            LocalDate date = dateOf(name);
            if (date == null || !date.isBefore(oldestKept)) continue;
            dropPartition(table, name);
        }
    }

    /**
     * 남길 가장 오래된 날짜.
     *
     * <p>{@code retentionDays} 는 <b>보관할 날짜 수</b>다 — 3이면 오늘·어제·그제 셋이다.
     * {@code today.minusDays(3)} 을 경계로 쓰면 네 날짜가 남아 스펙의 「최대 30일」이 31일이 된다.
     */
    public static LocalDate oldestKept(LocalDate today, int retentionDays) {
        return today.minusDays(Math.max(retentionDays - 1, 0));
    }

    /**
     * 아직 확정되지 않은 좌표가 남은 위치 파티션은 잠시 붙잡는다.
     *
     * <p>파기 타이머({@code purgeAfter})는 <b>확정 시각</b>에 걸린다. 그래서 확정 배치가 밀린 날의
     * 파티션을 시각만 보고 떨어뜨리면, 판정도 못 한 좌표를 <b>파기 기록조차 없이</b> 잃는다.
     * 스펙이 "고정 일괄 시각이 아니므로 확정 전 건이 지워지지 않는다"고 적은 지점이다.
     *
     * <p>다만 <b>무한정 붙잡지 않는다.</b> 인증에 한 번도 쓰이지 않은 좌표(위치 챌린지가 없는
     * 사용자의 신호)는 영영 확정되지 않아 파티션이 끝없이 쌓인다. 보관 기간의 두 배까지만
     * 기다리고, 그 뒤에는 몇 건을 확정 없이 지웠는지 남기고 떨어뜨린다.
     *
     * @return 이번에는 떨어뜨리지 않고 넘길지
     */
    private boolean holdForUnconfirmed(LocalDate partitionDate, LocalDate today, int retentionDays) {
        Integer unpurged = countUnconfirmed(partitionDate);
        if (unpurged == null || unpurged == 0) return false;

        boolean withinHold = !partitionDate.isBefore(today.minusDays(2L * retentionDays));
        if (withinHold) {
            log.warn("파기되지 않은 좌표가 남아 위치 파티션 파기를 미룬다 date={} rows={}",
                    partitionDate, unpurged);
            return true;
        }
        // 최대 보류 기간을 넘겼다. 대개 판정이 끝내 확정되지 않은 건인데, 무한정 붙잡으면
        // 파티션이 끝없이 쌓인다. 몇 건을 파기 기록 없이 지웠는지 남기고 떨어뜨린다.
        log.error("[PRIVACY] 파기 기록 없이 위치 파티션을 떨어뜨린다 — 확정이 끝내 되지 않은 건이다. "
                + "date={} rows={}", partitionDate, unpurged);
        return false;
    }

    /**
     * 그 귀속일의 위치 원본 중 <b>아직 확정되지 않은</b>(파기 타이머가 걸리지 않은) 행 수.
     * 파티션을 붙잡을지 정하는 유일한 입력이라 밖에서 확인할 수 있게 열어 둔다.
     */
    public Integer countUnconfirmed(LocalDate partitionDate) {
        try {
            // <b>파기되지 않은 채 남은 좌표</b>가 있으면 붙잡는다. 「파기 시각이 아직 안 왔는가」로
            // 물으면 안 된다 — 파기 시각은 적재 때 D+2 로 미리 박히므로, 파티션을 떨어뜨릴 때쯤이면
            // 판정이 아직 PENDING 이어도 그 조건은 이미 거짓이다. 그러면 확정 전 좌표가
            // 파티션째 사라진다. 파기 배치가 미확정 건을 건너뛰어 남겨 둔 행이 곧 그 신호다.
            return jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + SignalDomain.LOCATION.table()
                            + " WHERE observedDate = ? AND purgedAt IS NULL",
                    Integer.class, java.sql.Date.valueOf(partitionDate));
        } catch (RuntimeException e) {
            // 세지 못하면 붙잡는 쪽으로 기운다 — 판정 근거를 잃는 것보다 하루 더 두는 편이 낫다.
            log.warn("미파기 좌표 집계 실패 date={} err={}", partitionDate, e.toString());
            return Integer.MAX_VALUE;
        }
    }

    private void dropPartition(String table, String name) {
        try {
            jdbc.execute("ALTER TABLE " + table + " DROP PARTITION " + name);
            log.info("신호 파티션 파기 table={} partition={}", table, name);
        } catch (RuntimeException e) {
            log.warn("신호 파티션 파기 실패 table={} partition={} err={}", table, name, e.toString());
        }
    }

    private Set<String> existingPartitions(String table) {
        List<String> names = jdbc.queryForList(
                "SELECT PARTITION_NAME FROM information_schema.PARTITIONS"
                        + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?"
                        + " AND PARTITION_NAME IS NOT NULL",
                String.class, table);
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
