package com.ruleup.ruleup_backend.observability.metric;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 분 단위로 잘라 쌓는 지표 버퍼 — 1분 증분 카운터·분포·게이지를 모았다가 끝난 분만 내보낸다.
 *
 * <p>Micrometer 레지스트리를 쓰지 않는 이유는 둘이다. ① 노션 계약은 네임스페이스(RuleUp/SLI·Ops·Traffic·Security)·
 * 지표 이름·차원(Env,Service,Operation)을 고정해 두었는데, Micrometer CloudWatch 는 이름에 {@code .count} 같은 접미사를
 * 붙이고 네임스페이스가 레지스트리당 하나다. ② <b>실제 0 을 명시적으로 보내야 한다</b>. 배포 사전 점검(G04)은
 * 「요청이 없어서 0」과 「계측이 끊겨 시계열이 비었다」를 구분해야 하므로, {@link #declare 선언한} 카운터는
 * 그 분에 아무 일이 없어도 0 을 싣는다.
 *
 * <p>분의 경계는 데이터포인트의 timestamp(분 시작)다. 태스크가 여러 대면 각자 같은 timestamp 로 보내고,
 * CloudWatch 의 Sum 이 합친다. 그래서 카운터는 Sum, 게이지는 Maximum 으로 본다.
 */
public class MinuteMetricBuffer {

    /** 내보내지 않는 환경(로컬·시험)에서 분이 끝없이 쌓이지 않게 — 이보다 오래된 분은 버린다. */
    static final int MAX_PENDING_MINUTES = 15;

    /** 분포 원값을 이 개수 안에서만 구분한다 — PutMetricData 의 Values/Counts 는 데이터포인트당 150개가 상한이다. */
    static final int SIGNIFICANT_DIGITS = 2;

    private final Clock clock;
    private final Set<MetricKey> declared = ConcurrentHashMap.newKeySet();
    private final ConcurrentSkipListMap<Long, Map<MetricKey, Cell>> minutes = new ConcurrentSkipListMap<>();
    private volatile long nextMinuteToDrain;

    public MinuteMetricBuffer(Clock clock) {
        this.clock = clock;
        this.nextMinuteToDrain = currentMinute();
    }

    /** 이 카운터는 일이 없던 분에도 0 으로 보낸다. */
    public void declare(MetricKey key) {
        declared.add(key);
    }

    public void add(MetricKey key, long delta) {
        cell(key).sum.add(delta);
    }

    public void increment(MetricKey key) {
        add(key, 1);
    }

    /** 원값 분포에 한 건을 더한다(지연 p95 를 CloudWatch 가 직접 계산하게). */
    public void observe(MetricKey key, double value) {
        if (value < 0 || Double.isNaN(value)) return;
        cell(key).distribution.computeIfAbsent(round(value), v -> new LongAdder()).increment();
    }

    /** 그 분의 순간값 — 같은 분에 여러 번 넣으면 가장 큰 값이 남는다. */
    public void gauge(MetricKey key, double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return;
        cell(key).gaugeMax.accumulate(value);
    }

    /**
     * 끝난 분들을 꺼낸다(지금 진행 중인 분은 남긴다). 같은 분은 한 번만 나온다.
     * 선언한 카운터가 빠진 분에는 0 을 채운다.
     */
    public synchronized List<Datum> drainCompleted() {
        long current = currentMinute();
        List<Datum> out = new ArrayList<>();
        for (long m = nextMinuteToDrain; m < current; m++) {
            final long minute = m;
            Map<MetricKey, Cell> cells = minutes.remove(minute);
            Map<MetricKey, Cell> safe = cells == null ? Map.of() : cells;
            for (MetricKey key : declared) {
                if (!safe.containsKey(key)) out.add(Datum.sum(key, minute, 0));
            }
            safe.forEach((key, cell) -> out.addAll(cell.toData(key, minute)));
        }
        // 시계가 뒤로 가거나 드레인이 늦어 남은 옛 분(진행 중인 분보다 앞선 것)도 버리지 않고 내보낸다.
        for (Long stale : minutes.headMap(current).keySet()) {
            Map<MetricKey, Cell> cells = minutes.remove(stale);
            if (cells != null) cells.forEach((key, cell) -> out.addAll(cell.toData(key, stale)));
        }
        nextMinuteToDrain = Math.max(nextMinuteToDrain, current);
        return out;
    }

    private Cell cell(MetricKey key) {
        long minute = currentMinute();
        Map<MetricKey, Cell> cells = minutes.get(minute);
        if (cells == null) {
            cells = minutes.computeIfAbsent(minute, m -> new ConcurrentHashMap<>());
            while (minutes.size() > MAX_PENDING_MINUTES) minutes.pollFirstEntry();
        }
        return cells.computeIfAbsent(key, k -> new Cell());
    }

    private long currentMinute() {
        return Math.floorDiv(clock.millis(), 60_000L);
    }

    /** 유효숫자 2자리로 묶는다 — 1,234ms 와 1,240ms 를 같은 칸에 넣어 원값 종류를 150개 아래로 유지. */
    static double round(double value) {
        if (value == 0) return 0;
        double scale = Math.pow(10, Math.floor(Math.log10(Math.abs(value))) - (SIGNIFICANT_DIGITS - 1));
        return Math.round(value / scale) * scale;
    }

    private static final class Cell {
        final LongAdder sum = new LongAdder();
        final Map<Double, LongAdder> distribution = new ConcurrentHashMap<>();
        final java.util.concurrent.atomic.DoubleAccumulator gaugeMax =
                new java.util.concurrent.atomic.DoubleAccumulator(Math::max, Double.NEGATIVE_INFINITY);

        List<Datum> toData(MetricKey key, long minute) {
            List<Datum> data = new ArrayList<>(1);
            if (!distribution.isEmpty()) {
                Map<Double, Long> counts = new java.util.TreeMap<>();
                distribution.forEach((v, n) -> counts.put(v, n.sum()));
                data.add(new Datum(key, minute * 60_000L, null, counts));
            } else if (gaugeMax.get() != Double.NEGATIVE_INFINITY) {
                data.add(new Datum(key, minute * 60_000L, gaugeMax.get(), null));
            } else {
                data.add(Datum.sum(key, minute, sum.sum()));
            }
            return data;
        }
    }

    /**
     * 내보낼 데이터포인트 한 개. {@code value} 또는 {@code counts}(원값→건수) 중 하나만 있다.
     *
     * @param epochMillis 분 시작 시각
     */
    public record Datum(MetricKey key, long epochMillis, Double value, Map<Double, Long> counts) {
        static Datum sum(MetricKey key, long minute, double value) {
            return new Datum(key, minute * 60_000L, value, null);
        }
    }
}
