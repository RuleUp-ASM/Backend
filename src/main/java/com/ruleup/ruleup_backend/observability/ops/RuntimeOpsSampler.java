package com.ruleup.ruleup_backend.observability.ops;

import com.ruleup.ruleup_backend.observability.metric.MetricContext;
import com.ruleup.ruleup_backend.observability.metric.MetricKey;
import com.ruleup.ruleup_backend.observability.metric.MinuteMetricBuffer;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.context.ApplicationContext;
import com.ruleup.ruleup_backend.observability.metric.MinuteSampler;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * JVM·커넥션 풀·요청 스레드의 순간값을 30초마다({@link com.ruleup.ruleup_backend.observability.metric.MinuteMetricPublisher} 틱) 재 {@code RuleUp/Ops}(Env, Service[, Pool]) 로 보낸다 —
 * 노션 「알람 카탈로그」 §5 JVM-01/02·POOL-01~03·THREAD-01.
 *
 * <p>태스크마다 돈다. 같은 시계열에 태스크별 값이 겹쳐 들어가므로 경보는 <b>Maximum</b> 으로 「가장 나쁜 태스크」를
 * 본다. TaskId 는 차원에 넣지 않는다(태스크가 바뀔 때마다 새 과금 시계열이 생긴다). 풀 지표는 사용률(%)·대기 수라
 * 태스크 간 Max 가 곧 서비스 판정이고, 시간축 Sum 으로 커넥션 합을 만들지 않는다(노션 §8 「Hikari·태스크 차원」).
 *
 * <ul>
 *   <li>OldGenAfterGcPct — 마지막 GC 직후 Old 영역 점유율(GC 전 순간값은 톱니라 경보에 못 쓴다)</li>
 *   <li>GcPauseRatio5m — 최근 5분 GC 정지 합 ÷ 300초. 동시(concurrent) 수집 시간은 정지가 아니라 뺀다</li>
 *   <li>MaxPoolActivePct·MaxPoolPending·PoolTimeoutDelta — Hikari active/max, 커넥션 대기 스레드, 획득 timeout 증가분</li>
 *   <li>MaxBusyThreadPct — Tomcat 요청 스레드 busy/max</li>
 * </ul>
 *
 * <p>대시보드 OPS-6(JVM 런타임, 노션 10/15 리뷰) 용 — 경보 없이 화면에만 쓴다.
 * Micrometer {@code jvm.*} 를 통째로 내보내면 풀·원인별 태그 조합이 70개를 넘어(이름×차원 하나가 월 과금 단위) 필요한 것만 직접 잰다.
 * <ul>
 *   <li>JvmMemoryUsedMb(Area=eden·survivor·old·metaspace·codecache) · JvmHeapMaxMb</li>
 *   <li>JvmGcPauseMaxMs — Micrometer {@code jvm.gc.pause} 의 최근 최대(백분위는 설정이 없어 최대·횟수로 본다) · JvmGcPauseCount — 정지 수집 횟수 증가분</li>
 *   <li>JvmThreads(State=live·daemon·peak·runnable·blocked·waiting) · JvmClassesLoaded · JvmBufferUsedMb(Pool=direct·mapped)</li>
 *   <li>ProcessCpuPct · ProcessUptimeSec — 업타임이 내려가면 재기동(Minimum 으로 본다)</li>
 * </ul>
 */
@Slf4j
@Component
@Profile({"prod", "stg"})
public class RuntimeOpsSampler implements MinuteSampler {

    private static final long GC_WINDOW_MILLIS = 300_000L;

    private final MinuteMetricBuffer buffer;
    private final MetricContext ctx;
    private final ObjectProvider<DataSource> dataSources;
    private final MeterRegistry registry;
    private final ApplicationContext context;

    private final Deque<long[]> gcSamples = new ArrayDeque<>();   // [채집 시각 ms, 누적 GC 정지 ms]
    private final Map<String, Double> lastTimeouts = new HashMap<>();
    private long lastGcCount = -1;

    public RuntimeOpsSampler(MinuteMetricBuffer buffer, MetricContext ctx, ObjectProvider<DataSource> dataSources,
                             MeterRegistry registry, ApplicationContext context) {
        this.buffer = buffer;
        this.ctx = ctx;
        this.dataSources = dataSources;
        this.registry = registry;
        this.context = context;
    }

    @Override
    public void sample() {
        try { jvm(); } catch (RuntimeException e) { log.debug("jvm sample failed: {}", e.toString()); }
        try { pools(); } catch (RuntimeException e) { log.debug("pool sample failed: {}", e.toString()); }
        try { threads(); } catch (RuntimeException e) { log.debug("thread sample failed: {}", e.toString()); }
        try { jvmPanel(); } catch (RuntimeException e) { log.debug("jvm panel sample failed: {}", e.toString()); }
    }

    private void jvm() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() != MemoryType.HEAP || !isOldGen(pool.getName())) continue;
            MemoryUsage afterGc = pool.getCollectionUsage();
            if (afterGc == null) continue;
            long max = afterGc.getMax() > 0 ? afterGc.getMax() : Runtime.getRuntime().maxMemory();
            gauge("OldGenAfterGcPct", MetricKey.Unit.PERCENT, 100.0 * afterGc.getUsed() / max);
        }
        long now = System.currentTimeMillis();
        long pauses = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (isConcurrent(gc.getName())) continue;
            pauses += Math.max(0, gc.getCollectionTime());
        }
        gcSamples.addLast(new long[]{now, pauses});
        while (gcSamples.size() > 1 && now - gcSamples.peekFirst()[0] > GC_WINDOW_MILLIS) gcSamples.removeFirst();
        long[] oldest = gcSamples.peekFirst();
        if (oldest != null && now - oldest[0] >= GC_WINDOW_MILLIS - 60_000L) {   // 창이 4분 이상 찼을 때부터
            gauge("GcPauseRatio5m", MetricKey.Unit.PERCENT, 100.0 * (pauses - oldest[1]) / GC_WINDOW_MILLIS);
        }
    }

    private void pools() {
        dataSources.orderedStream().forEach(ds -> {
            if (!(ds instanceof HikariDataSource hikari) || hikari.getHikariPoolMXBean() == null) return;
            String pool = hikari.getPoolName();
            var mx = hikari.getHikariPoolMXBean();
            int max = hikari.getMaximumPoolSize();
            var d = ctx.dims("Pool", pool);
            buffer.gauge(new MetricKey(OpsSignals.OPS, "MaxPoolActivePct", d, MetricKey.Unit.PERCENT),
                    max > 0 ? 100.0 * mx.getActiveConnections() / max : 0);
            buffer.gauge(new MetricKey(OpsSignals.OPS, "MaxPoolPending", d, MetricKey.Unit.COUNT),
                    mx.getThreadsAwaitingConnection());
            Counter timeouts = registry.find("hikaricp.connections.timeout").tag("pool", pool).counter();
            if (timeouts != null) {
                double total = timeouts.count();
                Double previous = lastTimeouts.put(pool, total);
                long delta = previous == null ? 0 : Math.max(0, Math.round(total - previous));
                buffer.add(MetricKey.count(OpsSignals.OPS, "PoolTimeoutDelta", d), delta);
            }
        });
    }

    /**
     * <b>{@code Tomcat#getConnector()} 를 쓰지 않는다.</b> 그 메서드는 커넥터가 없으면 기본 커넥터를 <b>새로 만들어 8080 에
     * 바인드</b>한다. 스프링 부트는 기동 중 서비스 커넥터를 잠시 떼어 두므로, 그 사이에 이 틱이 돌면 가짜 커넥터가 포트를
     * 먼저 잡고 진짜 웹서버가 「Port 8080 was already in use」로 기동에 실패한다(2026-10-12 stg 배포 실패).
     * {@code findConnectors()} 는 있는 것만 돌려준다 — 기동 전이면 비어 있어 건너뛴다.
     */
    private void threads() {
        if (!(context instanceof WebServerApplicationContext web) || !(web.getWebServer() instanceof TomcatWebServer tomcat)) return;
        for (var connector : tomcat.getTomcat().getService().findConnectors()) {
            var executor = connector.getProtocolHandler().getExecutor();
            if (executor instanceof org.apache.tomcat.util.threads.ThreadPoolExecutor pool && pool.getMaximumPoolSize() > 0) {
                gauge("MaxBusyThreadPct", MetricKey.Unit.PERCENT, 100.0 * pool.getActiveCount() / pool.getMaximumPoolSize());
                return;
            }
        }
    }

    private void jvmPanel() {
        Map<String, Long> areas = new TreeMap<>();
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            String area = memoryArea(pool.getName());
            if (area != null) areas.merge(area, pool.getUsage().getUsed(), Long::sum);
        }
        areas.forEach((area, used) -> gauge("JvmMemoryUsedMb", MetricKey.Unit.NONE, mb(used), "Area", area));
        long heapMax = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax();
        if (heapMax > 0) gauge("JvmHeapMaxMb", MetricKey.Unit.NONE, mb(heapMax));

        double pauseMax = registry.find("jvm.gc.pause").timers().stream()
                .mapToDouble(t -> t.max(TimeUnit.MILLISECONDS)).max().orElse(Double.NaN);
        if (!Double.isNaN(pauseMax)) gauge("JvmGcPauseMaxMs", MetricKey.Unit.MILLISECONDS, pauseMax);
        long gcCount = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (!isConcurrent(gc.getName())) gcCount += Math.max(0, gc.getCollectionCount());
        }
        if (lastGcCount >= 0) buffer.add(MetricKey.count(OpsSignals.OPS, "JvmGcPauseCount", ctx.dims()), Math.max(0, gcCount - lastGcCount));
        lastGcCount = gcCount;

        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        Map<Thread.State, Integer> states = new EnumMap<>(Thread.State.class);
        for (ThreadInfo info : threads.getThreadInfo(threads.getAllThreadIds(), 0)) {
            if (info != null) states.merge(info.getThreadState(), 1, Integer::sum);
        }
        gauge("JvmThreads", MetricKey.Unit.COUNT, threads.getThreadCount(), "State", "live");
        gauge("JvmThreads", MetricKey.Unit.COUNT, threads.getDaemonThreadCount(), "State", "daemon");
        gauge("JvmThreads", MetricKey.Unit.COUNT, threads.getPeakThreadCount(), "State", "peak");
        gauge("JvmThreads", MetricKey.Unit.COUNT, states.getOrDefault(Thread.State.RUNNABLE, 0), "State", "runnable");
        gauge("JvmThreads", MetricKey.Unit.COUNT, states.getOrDefault(Thread.State.BLOCKED, 0), "State", "blocked");
        gauge("JvmThreads", MetricKey.Unit.COUNT, states.getOrDefault(Thread.State.WAITING, 0)
                + states.getOrDefault(Thread.State.TIMED_WAITING, 0), "State", "waiting");

        gauge("JvmClassesLoaded", MetricKey.Unit.COUNT, ManagementFactory.getClassLoadingMXBean().getLoadedClassCount());
        for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
            String name = pool.getName().toLowerCase(Locale.ROOT);
            if (name.equals("direct") || name.equals("mapped")) gauge("JvmBufferUsedMb", MetricKey.Unit.NONE, mb(pool.getMemoryUsed()), "Pool", name);
        }
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os && os.getProcessCpuLoad() >= 0) {
            gauge("ProcessCpuPct", MetricKey.Unit.PERCENT, 100.0 * os.getProcessCpuLoad());
        }
        gauge("ProcessUptimeSec", MetricKey.Unit.SECONDS, ManagementFactory.getRuntimeMXBean().getUptime() / 1000.0);
    }

    private void gauge(String name, MetricKey.Unit unit, double value, String... extraDims) {
        buffer.gauge(new MetricKey(OpsSignals.OPS, name, ctx.dims(extraDims), unit), value);
    }

    private static double mb(long bytes) {
        return bytes / (1024.0 * 1024.0);
    }

    /** 메모리 풀 이름 → 대시보드 영역. G1·Parallel·Serial·ZGC 이름을 묶는다. 모르는 풀(Compressed Class 등)은 null — 내보내지 않는다. */
    static String memoryArea(String poolName) {
        if (poolName.contains("Eden") || poolName.equals("ZGC Young Generation")) return "eden";
        if (poolName.contains("Survivor")) return "survivor";
        if (isOldGen(poolName)) return "old";
        if (poolName.equals("Metaspace")) return "metaspace";
        if (poolName.startsWith("CodeHeap") || poolName.equals("Code Cache")) return "codecache";
        return null;
    }

    static boolean isOldGen(String poolName) {
        return poolName.contains("Old Gen") || poolName.contains("Tenured") || poolName.equals("ZGC Old Generation");
    }

    /** G1 Concurrent GC·ZGC Cycles 처럼 애플리케이션을 멈추지 않는 수집기. */
    static boolean isConcurrent(String collectorName) {
        return collectorName.contains("Concurrent") || collectorName.contains("Cycles");
    }
}
