package com.ruleup.ruleup_backend.observability;

import com.ruleup.ruleup_backend.observability.domain.SystemMetricSnapshot;
import com.ruleup.ruleup_backend.observability.repository.SystemMetricSnapshotRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.CentralProcessor.TickType;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HWDiskStore;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.NetworkIF;
import oshi.hardware.VirtualMemory;
import oshi.software.os.InternetProtocolStats;
import oshi.software.os.OSFileStore;
import oshi.software.os.OperatingSystem;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;

/**
 * 시스템 자원 지표 샘플러(개발기간 관찰용). OSHI로 OS/하드웨어 지표를 주기 수집해
 * 로그 1줄 + {@link SystemMetricSnapshot} 1행으로 남긴다.
 *
 * <p>수집 항목: CPU(user/system/iowait), 메모리+Swap, 디스크(사용률/IOPS/여유), 네트워크(In/Out 대역폭·연결 수).
 * 대역폭·IOPS 는 누적 카운터라 <b>직전 샘플과의 델타 ÷ 경과초</b>로 순간율을 계산한다(첫 샘플은 rate=null).
 *
 * <p><b>프로파일</b>: {@code !test} — 테스트(CI)에서는 OSHI 네이티브 호출·DB 적재를 돌리지 않는다.
 * 스케줄러 스레드 단일 실행이라 직전 카운터 필드에 동시성 문제는 없다.
 * 어떤 지표 하나가 플랫폼에서 실패해도 스케줄러가 죽지 않도록 전 구간을 방어한다.
 */
@Component
@Profile("!test")
@RequiredArgsConstructor
public class SystemMetricsSampler {

    private static final Logger log = LoggerFactory.getLogger(SystemMetricsSampler.class);

    private final SystemMetricSnapshotRepository repository;

    @Value("${app.observability.metrics.retention-days:14}")
    private int retentionDays;

    private SystemInfo systemInfo;
    private HardwareAbstractionLayer hal;
    private OperatingSystem os;
    private CentralProcessor cpu;

    // 순간율 계산용 직전 누적 카운터(스케줄러 단일 스레드라 락 불필요)
    private boolean hasPrev = false;
    private long[] prevCpuTicks;
    private long prevDiskReads;
    private long prevDiskWrites;
    private long prevNetRecv;
    private long prevNetSent;
    private long prevSampleNanos;

    @PostConstruct
    void init() {
        this.systemInfo = new SystemInfo();
        this.hal = systemInfo.getHardware();
        this.os = systemInfo.getOperatingSystem();
        this.cpu = hal.getProcessor();
    }

    /** 기본 60초 주기(app.observability.metrics.sample-interval-ms). 초기 지연 후 첫 샘플은 rate 미포함. */
    @Scheduled(fixedDelayString = "${app.observability.metrics.sample-interval-ms:60000}",
            initialDelayString = "${app.observability.metrics.sample-interval-ms:60000}")
    @Transactional
    public void sample() {
        try {
            long nowNanos = System.nanoTime();
            double elapsedSec = hasPrev ? (nowNanos - prevSampleNanos) / 1_000_000_000.0 : 0.0;
            SystemMetricSnapshot snap = SystemMetricSnapshot.at(Instant.now());

            sampleCpu(snap);
            sampleMemory(snap);
            sampleDisk(snap, elapsedSec);
            sampleNetwork(snap, elapsedSec);

            repository.save(snap);
            prevSampleNanos = nowNanos;
            hasPrev = true;

            log.info("[SYS] cpu(u/s/io)={}/{}/{}% mem={}%({} MB) swap={} MB disk={}% free={} GB "
                            + "iops(r/w)={}/{} net(in/out)={}/{} KB/s conn={}",
                    snap.getCpuUserPct(), snap.getCpuSystemPct(), snap.getCpuIoWaitPct(),
                    snap.getMemUsedPct(), mb(snap.getMemUsedBytes()), mb(snap.getSwapUsedBytes()),
                    snap.getDiskUsedPct(), gb(snap.getDiskFreeBytes()),
                    snap.getDiskReadsPerSec(), snap.getDiskWritesPerSec(),
                    kb(snap.getNetInBytesPerSec()), kb(snap.getNetOutBytesPerSec()), snap.getTcpConnEstablished());
        } catch (Exception e) {
            log.warn("시스템 지표 샘플링 실패: {}", e.getMessage());
        }
    }

    /** 매일 04:15 KST: 보관기간 초과 스냅샷 정리(무한 증가 방지). */
    @Scheduled(cron = "0 15 4 * * *", zone = "Asia/Seoul")
    @Transactional
    public void cleanupOld() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
        int deleted = repository.deleteByCapturedAtBefore(cutoff);
        if (deleted > 0) {
            log.info("시스템 지표 스냅샷 {}건 정리(보관 {}일 초과)", deleted, retentionDays);
        }
    }

    private void sampleCpu(SystemMetricSnapshot snap) {
        long[] ticks = cpu.getSystemCpuLoadTicks();
        if (hasPrev && prevCpuTicks != null) {
            long total = 0;
            for (int i = 0; i < ticks.length; i++) total += Math.max(0, ticks[i] - prevCpuTicks[i]);
            if (total > 0) {
                snap.cpu(
                        pct(tickDelta(ticks, TickType.USER), total),
                        pct(tickDelta(ticks, TickType.SYSTEM), total),
                        pct(tickDelta(ticks, TickType.IOWAIT), total));
            }
        }
        prevCpuTicks = ticks;
    }

    private long tickDelta(long[] ticks, TickType type) {
        int i = type.getIndex();
        return Math.max(0, ticks[i] - prevCpuTicks[i]);
    }

    private void sampleMemory(SystemMetricSnapshot snap) {
        GlobalMemory mem = hal.getMemory();
        long total = mem.getTotal();
        long used = total - mem.getAvailable();
        VirtualMemory vm = mem.getVirtualMemory();
        snap.memory(pct(used, total), used, total, vm.getSwapUsed(), vm.getSwapTotal());
    }

    private void sampleDisk(SystemMetricSnapshot snap, double elapsedSec) {
        // 물리 디스크 누적 IO 카운트 → IOPS
        long reads = 0, writes = 0;
        for (HWDiskStore d : hal.getDiskStores()) {
            reads += d.getReads();
            writes += d.getWrites();
        }
        BigDecimal readsPerSec = null, writesPerSec = null;
        if (hasPrev && elapsedSec > 0) {
            readsPerSec = rate(reads - prevDiskReads, elapsedSec);
            writesPerSec = rate(writes - prevDiskWrites, elapsedSec);
        }
        prevDiskReads = reads;
        prevDiskWrites = writes;

        // 파일시스템 합산 사용률/여유
        long fsTotal = 0, fsUsable = 0;
        for (OSFileStore s : os.getFileSystem().getFileStores()) {
            fsTotal += s.getTotalSpace();
            fsUsable += s.getUsableSpace();
        }
        BigDecimal diskUsedPct = fsTotal > 0 ? pct(fsTotal - fsUsable, fsTotal) : null;
        snap.disk(diskUsedPct, fsTotal > 0 ? fsUsable : null, fsTotal > 0 ? fsTotal : null, readsPerSec, writesPerSec);
    }

    private void sampleNetwork(SystemMetricSnapshot snap, double elapsedSec) {
        long recv = 0, sent = 0;
        for (NetworkIF n : hal.getNetworkIFs()) {
            recv += n.getBytesRecv();
            sent += n.getBytesSent();
        }
        BigDecimal inRate = null, outRate = null;
        if (hasPrev && elapsedSec > 0) {
            inRate = rate(recv - prevNetRecv, elapsedSec);
            outRate = rate(sent - prevNetSent, elapsedSec);
        }
        prevNetRecv = recv;
        prevNetSent = sent;

        Integer conn = null;
        try {
            InternetProtocolStats ip = os.getInternetProtocolStats();
            long established = ip.getTCPv4Stats().getConnectionsEstablished()
                    + ip.getTCPv6Stats().getConnectionsEstablished();
            conn = (int) established;
        } catch (Exception ignore) {
            // 일부 플랫폼은 established 카운트 미제공 — 연결 수만 생략
        }
        snap.network(inRate, outRate, conn);
    }

    // ===== 헬퍼 =====

    /** part/total 백분율(소수 2자리). total<=0 이면 null. */
    private static BigDecimal pct(long part, long total) {
        if (total <= 0) return null;
        return BigDecimal.valueOf(100.0 * part / total).setScale(2, RoundingMode.HALF_UP);
    }

    /** 델타 ÷ 경과초. 카운터 리셋(음수 델타)이면 null 로 무시. */
    private static BigDecimal rate(long delta, double elapsedSec) {
        if (delta < 0 || elapsedSec <= 0) return null;
        return BigDecimal.valueOf(delta / elapsedSec).setScale(2, RoundingMode.HALF_UP);
    }

    private static Long mb(Long bytes) {
        return bytes == null ? null : bytes / (1024 * 1024);
    }

    private static Long gb(Long bytes) {
        return bytes == null ? null : bytes / (1024 * 1024 * 1024);
    }

    private static BigDecimal kb(BigDecimal bytesPerSec) {
        return bytesPerSec == null ? null : bytesPerSec.divide(BigDecimal.valueOf(1024), 2, RoundingMode.HALF_UP);
    }
}
