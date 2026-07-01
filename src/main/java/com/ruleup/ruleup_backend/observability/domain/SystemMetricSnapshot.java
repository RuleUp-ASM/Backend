package com.ruleup.ruleup_backend.observability.domain;

import com.ruleup.ruleup_backend.common.AssignedIdEntity;
import com.ruleup.ruleup_backend.common.UuidGenerator;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 시스템 자원 지표 스냅샷(SystemMetricSnapshot). 개발기간 관찰용.
 *  - {@code SystemMetricsSampler}가 주기적으로(기본 60초) OSHI로 수집해 1행씩 적재.
 *  - CPU(user/system/iowait), 메모리+Swap, 디스크(사용률/IOPS/여유), 네트워크(In/Out 대역폭/연결 수).
 *  - 대역폭·IOPS는 순간율(직전 샘플과의 델타 ÷ 경과초). 첫 샘플은 직전값이 없어 rate 계열이 null.
 *  - 값이 없거나(플랫폼 미지원) 첫 샘플이면 해당 컬럼은 null 로 둔다.
 *  - 보관은 {@code SystemMetricsSampler}의 정리 배치가 retentionDays 지난 행을 삭제.
 */
@Entity
@Table(name = "SystemMetricSnapshot")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SystemMetricSnapshot extends AssignedIdEntity {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "capturedAt", nullable = false, updatable = false)
    private Instant capturedAt;

    // ===== CPU 사용률(%) =====
    @Column(name = "cpuUserPct", precision = 5, scale = 2)
    private BigDecimal cpuUserPct;
    @Column(name = "cpuSystemPct", precision = 5, scale = 2)
    private BigDecimal cpuSystemPct;
    @Column(name = "cpuIoWaitPct", precision = 5, scale = 2)
    private BigDecimal cpuIoWaitPct;

    // ===== 메모리 / Swap =====
    @Column(name = "memUsedPct", precision = 5, scale = 2)
    private BigDecimal memUsedPct;
    @Column(name = "memUsedBytes")
    private Long memUsedBytes;
    @Column(name = "memTotalBytes")
    private Long memTotalBytes;
    @Column(name = "swapUsedBytes")
    private Long swapUsedBytes;
    @Column(name = "swapTotalBytes")
    private Long swapTotalBytes;

    // ===== 디스크(파일시스템 합산 + 물리 디스크 IOPS) =====
    @Column(name = "diskUsedPct", precision = 5, scale = 2)
    private BigDecimal diskUsedPct;
    @Column(name = "diskFreeBytes")
    private Long diskFreeBytes;
    @Column(name = "diskTotalBytes")
    private Long diskTotalBytes;
    @Column(name = "diskReadsPerSec", precision = 12, scale = 2)
    private BigDecimal diskReadsPerSec;
    @Column(name = "diskWritesPerSec", precision = 12, scale = 2)
    private BigDecimal diskWritesPerSec;

    // ===== 네트워크(전체 인터페이스 합산) =====
    @Column(name = "netInBytesPerSec", precision = 16, scale = 2)
    private BigDecimal netInBytesPerSec;
    @Column(name = "netOutBytesPerSec", precision = 16, scale = 2)
    private BigDecimal netOutBytesPerSec;
    @Column(name = "tcpConnEstablished")
    private Integer tcpConnEstablished;

    private SystemMetricSnapshot(Instant capturedAt) {
        this.id = UuidGenerator.generate();
        this.capturedAt = capturedAt;
    }

    /** 수집 시각만 세팅한 빈 스냅샷 개시. 지표는 빌더 메서드로 채운다. */
    public static SystemMetricSnapshot at(Instant capturedAt) {
        return new SystemMetricSnapshot(capturedAt);
    }

    public SystemMetricSnapshot cpu(BigDecimal user, BigDecimal system, BigDecimal ioWait) {
        this.cpuUserPct = user;
        this.cpuSystemPct = system;
        this.cpuIoWaitPct = ioWait;
        return this;
    }

    public SystemMetricSnapshot memory(BigDecimal usedPct, Long usedBytes, Long totalBytes,
                                       Long swapUsedBytes, Long swapTotalBytes) {
        this.memUsedPct = usedPct;
        this.memUsedBytes = usedBytes;
        this.memTotalBytes = totalBytes;
        this.swapUsedBytes = swapUsedBytes;
        this.swapTotalBytes = swapTotalBytes;
        return this;
    }

    public SystemMetricSnapshot disk(BigDecimal usedPct, Long freeBytes, Long totalBytes,
                                     BigDecimal readsPerSec, BigDecimal writesPerSec) {
        this.diskUsedPct = usedPct;
        this.diskFreeBytes = freeBytes;
        this.diskTotalBytes = totalBytes;
        this.diskReadsPerSec = readsPerSec;
        this.diskWritesPerSec = writesPerSec;
        return this;
    }

    public SystemMetricSnapshot network(BigDecimal inBytesPerSec, BigDecimal outBytesPerSec, Integer tcpConnEstablished) {
        this.netInBytesPerSec = inBytesPerSec;
        this.netOutBytesPerSec = outBytesPerSec;
        this.tcpConnEstablished = tcpConnEstablished;
        return this;
    }
}
