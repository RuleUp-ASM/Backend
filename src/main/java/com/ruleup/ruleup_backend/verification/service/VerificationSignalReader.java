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
 * <p>그래서 평가할 때마다 그 귀속일의 원본을 <b>전량</b> 읽어 처음부터 다시 계산한다. 같은 신호
 * 집합이면 언제 평가하든 같은 결과가 나오고(멱등), 요약을 이월할 필요가 사라진다.
 *
 * <h4>왜 사흘치를 읽는가</h4>
 * 적재 시 귀속일은 <b>신호 봉투의 관측 시각</b>으로 정해지는데, 그 안의 항목은 날짜 경계를
 * 걸칠 수 있다 — 23:50 에 관측된 앱 사용 묶음에 00:10 이벤트가 들어 있는 식이다. 수면은 아예
 * "밤이 시작된 날짜"라는 별도 규칙을 쓴다. 그래서 D-1\~D+1 파티션을 읽어 항목 단위 귀속은
 * {@code DaySignals}·평가기에 맡긴다. 파티션 셋과 {@code userId} 로 좁히므로 전체 스캔이 아니다.
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

    /**
     * 한 귀속일·한 도메인에서 읽을 원본 상한(방어적).
     *
     * <p>여기 걸리면 <b>그 날 판정은 전량 재평가가 아니다</b> — 잘린 만큼이 근거에서 빠진다.
     * 조용히 넘어가면 「원본이 곧 판정의 원본」이라는 전제가 소리 없이 깨지므로, 걸린 사실을
     * 세고 남긴다. 정상 사용자는 근처에도 오지 않는 수치다(1분 sync 를 하루 종일 해도 1,440건).
     */
    private static final int MAX_ROWS_PER_DAY = 20_000;

    private final JdbcTemplate jdbc;
    private final VerificationMetrics metrics;

    /**
     * 그 귀속일 판정에 쓸 원본 신호 전부.
     *
     * <p>여러 챌린지가 같은 사용자 신호를 공유하므로 챌린지별로 복제해 읽지 않는다 — 호출부가
     * 한 번 읽어 멤버들에게 돌린다.
     */
    @Transactional(readOnly = true)
    public List<SyncSignal> forDay(UUID userId, LocalDate targetDate) {
        List<SyncSignal> out = new ArrayList<>();
        for (SignalDomain domain : SignalDomain.values()) {
            out.addAll(read(userId, domain, targetDate));
        }
        return out;
    }

    /** 여러 귀속일을 한 번에. sync 는 오늘과 유예 중인 어제를 함께 평가한다. */
    @Transactional(readOnly = true)
    public Map<LocalDate, List<SyncSignal>> forDays(UUID userId, List<LocalDate> targetDates) {
        Map<LocalDate, List<SyncSignal>> byDate = new HashMap<>();
        for (LocalDate date : targetDates) byDate.put(date, forDay(userId, date));
        return byDate;
    }

    private List<SyncSignal> read(UUID userId, SignalDomain domain, LocalDate targetDate) {
        List<String> payloads = jdbc.queryForList(
                "SELECT payload FROM " + domain.table()
                        + " WHERE observedDate BETWEEN ? AND ? AND userId = ? AND excludeReason IS NULL"
                        + " ORDER BY occurredAt, id LIMIT " + (MAX_ROWS_PER_DAY + 1),
                String.class,
                Date.valueOf(targetDate.minusDays(1)), Date.valueOf(targetDate.plusDays(1)), bytes(userId));

        if (payloads.size() > MAX_ROWS_PER_DAY) {
            // 잘린 채로 판정하면 사용 시간·체류가 실제보다 작게 나온다. 유저에게 불리한 방향이라
            // 더더욱 묻어 두면 안 된다.
            metrics.signalsReadTruncated();
            log.error("일별 원본 조회가 상한에 걸렸다 — 이 날 판정은 전량 재평가가 아니다. "
                    + "table={} userId={} date={} limit={}", domain.table(), userId, targetDate, MAX_ROWS_PER_DAY);
            payloads = payloads.subList(0, MAX_ROWS_PER_DAY);
        }

        List<SyncSignal> signals = new ArrayList<>(payloads.size());
        for (String payload : payloads) {
            SyncSignal signal = parse(payload);
            if (signal != null) signals.add(signal);
        }
        return signals;
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

    private static byte[] bytes(UUID id) {
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(id.getMostSignificantBits());
        bb.putLong(id.getLeastSignificantBits());
        return bb.array();
    }
}
