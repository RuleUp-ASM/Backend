package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.verification.signal.SignalDomain;
import com.ruleup.ruleup_backend.verification.signal.SyncSignal;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 판정 입력 조회 — <b>원본 신호가 판정의 원본이다</b> (백엔드 4-1-1 · 4-3 「현재 상태 평가」).
 *
 * <h4>왜 다시 읽는가</h4>
 * 예전에는 「이번 요청에 새로 들어온 신호 + 직전 요약」으로 판정했다. 그러면 판정이 <b>도착
 * 순서에 의존</b>한다 — 분할 전송이 뒤바뀌어 PAUSED 가 RESUMED 보다 먼저 오거나 EXIT 이 ENTER
 * 보다 먼저 오면, 짝을 못 찾은 앞 이벤트가 영구히 버려진다. 스펙이 "나누어 보낸 요청은 순서가
 * 바뀌어도 되도록 신호 단위로 처리함"이라고 못 박은 지점이다.
 *
 * <p>그래서 평가할 때마다 그 귀속일의 원본을 <b>전량</b> 읽어 처음부터 다시 계산한다.
 *
 * <h4>전량이란 진짜 전량이다</h4>
 * 한 번에 상한만큼만 읽고 자르면 「전량 재평가」가 아니라 <b>잘린 일부로 내린 결론</b>이다.
 * 그래서 날짜별로 <b>페이지를 이어 읽는다.</b> 그래도 절대 상한을 넘으면 잘린 값으로 확정하지
 * 않고 {@link IncompleteSignalWindowException} 을 던져 판정을 미룬다 — 잘린 쪽에 위반 신호가
 * 있었다면 규칙 지키기형이 잘못 성공한다.
 *
 * <h4>왜 사흘치를 읽는가</h4>
 * 적재 시 귀속일은 <b>신호 봉투의 관측 시각</b>으로 정해지는데, 그 안의 항목은 날짜 경계를
 * 걸칠 수 있다 — 23:50 에 관측된 앱 사용 묶음에 00:10 이벤트가 들어 있는 식이다. 수면은 아예
 * "밤이 시작된 날짜"라는 별도 규칙을 쓴다. 그래서 D-1\~D+1 을 읽어 항목 단위 귀속은
 * {@code DaySignals}·평가기에 맡긴다. <b>하루씩 따로 읽어</b> 매 질의가 파티션 하나로 좁혀진다.
 *
 * <h4>배제된 행은 읽지 않는다</h4>
 * 게이트가 사유를 새긴 행({@code excludeReason})은 판정 입력에서 뺀다. 원본은 남아 있고
 * 이상탐지가 본다 — 배제와 제재를 분리하라는 스펙의 경계가 여기다.
 */
@Component
@RequiredArgsConstructor
public class VerificationSignalReader {

    private static final Logger log = LoggerFactory.getLogger(VerificationSignalReader.class);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 한 번에 읽어 올 행 수. 키셋으로 이어 읽으므로 깊은 OFFSET 비용이 없다. */
    private static final int PAGE_SIZE = 5_000;

    /**
     * 한 도메인·한 날짜에서 읽을 절대 상한.
     *
     * <p>여기 닿으면 그 날 판정을 <b>확정하지 않는다.</b> 정상 사용자는 근처에도 오지 않는
     * 수치다 — 1분 sync 를 하루 종일 해도 1,440건이다. 닿았다면 클라이언트 이상이거나
     * 공격이고, 어느 쪽이든 그 데이터로 결론을 내면 안 된다.
     */
    private static final int MAX_ROWS_PER_DAY = 200_000;

    private final JdbcTemplate jdbc;
    private final VerificationMetrics metrics;

    /** 하루치 원본과 <b>전부 읽었는지</b>. 전부가 아니면 판정을 미뤄야 한다. */
    public record DaySignalSet(List<SyncSignal> signals, boolean complete) {
        static DaySignalSet complete(List<SyncSignal> signals) { return new DaySignalSet(signals, true); }
        static DaySignalSet truncated(List<SyncSignal> signals) { return new DaySignalSet(signals, false); }
    }

    /**
     * 그 귀속일 판정에 쓸 원본 신호 전부.
     *
     * <p>여러 챌린지가 같은 사용자 신호를 공유하므로 챌린지별로 복제해 읽지 않는다 — 호출부가
     * 한 번 읽어 멤버들에게 돌린다.
     *
     * @throws IncompleteSignalWindowException 상한에 닿아 전량을 읽지 못한 경우
     */
    @Transactional(readOnly = true)
    public List<SyncSignal> forDay(UUID userId, LocalDate targetDate) {
        DaySignalSet set = read(userId, targetDate);
        if (!set.complete()) {
            throw new IncompleteSignalWindowException(userId, targetDate, "verification_*_signals", MAX_ROWS_PER_DAY);
        }
        return set.signals();
    }

    /**
     * 여러 귀속일을 한 번에. sync 는 오늘과 유예 중인 어제를 함께 평가한다.
     *
     * <p>전량을 못 읽은 날은 <b>예외 대신 표시</b>로 돌려준다 — sync 요청 하나를 통째로 실패시키면
     * 그 유저의 다른 날짜·다른 챌린지 판정까지 함께 멈춘다. 호출부가 그 날만 건너뛴다.
     */
    @Transactional(readOnly = true)
    public Map<LocalDate, DaySignalSet> forDays(UUID userId, List<LocalDate> targetDates) {
        Map<LocalDate, DaySignalSet> byDate = new HashMap<>();
        for (LocalDate date : targetDates) byDate.put(date, read(userId, date));
        return byDate;
    }

    private DaySignalSet read(UUID userId, LocalDate targetDate) {
        List<SyncSignal> out = new ArrayList<>();
        boolean complete = true;
        for (SignalDomain domain : SignalDomain.values()) {
            // 항목이 날짜 경계를 걸치므로 앞뒤 하루를 함께 읽는다. 하루씩 따로 질의해
            // 매 질의가 파티션 하나로 좁혀진다.
            for (LocalDate date : List.of(targetDate.minusDays(1), targetDate, targetDate.plusDays(1))) {
                complete &= readDay(userId, domain, date, out);
            }
        }
        return complete ? DaySignalSet.complete(out) : DaySignalSet.truncated(out);
    }

    /**
     * 한 도메인·한 날짜를 키셋으로 이어 읽는다.
     *
     * @return 전부 읽었으면 true, 절대 상한에 닿아 멈췄으면 false
     */
    private boolean readDay(UUID userId, SignalDomain domain, LocalDate date, List<SyncSignal> out) {
        byte[] cursor = new byte[16];   // binary(16) 최솟값 — 첫 페이지
        int read = 0;
        while (true) {
            List<Map<String, Object>> page = jdbc.queryForList(
                    "SELECT id, payload, receivedAt FROM " + domain.table()
                            + " WHERE observedDate = ? AND userId = ? AND excludeReason IS NULL AND id > ?"
                            + " ORDER BY id LIMIT " + PAGE_SIZE,
                    Date.valueOf(date), bytes(userId), cursor);
            if (page.isEmpty()) return true;

            for (Map<String, Object> row : page) {
                SyncSignal signal = parse((String) row.get("payload"));
                // 수신 시각은 payload 가 아니라 행에 있다 — 평가기가 「받은 때」로 따질 수 있게 채워 준다.
                if (signal != null) out.add(signal.withReceivedAt(receivedAt(row.get("receivedAt"))));
                cursor = (byte[]) row.get("id");
            }
            read += page.size();
            if (page.size() < PAGE_SIZE) return true;   // 마지막 페이지

            if (read >= MAX_ROWS_PER_DAY) {
                metrics.signalsReadTruncated();
                log.error("일별 원본이 절대 상한을 넘었다 — 이 날 판정을 확정하지 않는다. "
                                + "table={} userId={} date={} read={}",
                        domain.table(), userId, date, read);
                return false;
            }
        }
    }

    /**
     * 저장해 둔 원본 JSON → 신호. 깨진 행 하나가 그날 판정 전체를 막지 않도록 건너뛴다 —
     * 계약이 바뀐 직후의 예전 payload 가 여기로 올 수 있다.
     */
    private SyncSignal parse(String payload) {
        try {
            return JSON.readValue(payload, SyncSignal.class);
        } catch (RuntimeException e) {
            log.warn("원본 신호를 해석하지 못했다 — 그 행만 건너뛴다. err={}", e.toString());
            return null;
        }
    }

    /** {@code datetime(3|6)} 는 드라이버에 따라 Timestamp 또는 LocalDateTime 으로 온다. 둘 다 UTC 로 읽는다. */
    private static java.time.Instant receivedAt(Object raw) {
        if (raw instanceof java.sql.Timestamp ts) return ts.toInstant();
        if (raw instanceof java.time.LocalDateTime dt) return dt.toInstant(java.time.ZoneOffset.UTC);
        return null;
    }

    private static byte[] bytes(UUID id) {
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(id.getMostSignificantBits());
        bb.putLong(id.getLeastSignificantBits());
        return bb.array();
    }
}
