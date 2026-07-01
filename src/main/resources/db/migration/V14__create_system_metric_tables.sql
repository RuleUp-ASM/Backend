-- 경로: src/main/resources/db/migration/V14__create_system_metric_tables.sql
-- 시스템 자원 지표 스냅샷(개발기간 관찰용). SystemMetricsSampler가 주기(기본 60초) 수집해 1행씩 적재.
--   · CPU(user/system/iowait), 메모리+Swap, 디스크(사용률/IOPS/여유), 네트워크(In/Out 대역폭·연결 수).
--   · 대역폭·IOPS는 순간율(직전 샘플 델타 ÷ 경과초) — 첫 샘플은 rate 계열이 NULL.
--   · 플랫폼 미지원/첫 샘플이면 해당 컬럼 NULL 허용.
--   · 보관은 샘플러의 정리 배치가 retentionDays 초과분 삭제(무한 증가 방지). FK 없음(다른 도메인과 독립).
--   · 네이밍은 기존 테이블 컨벤션(camelCase) 유지.

CREATE TABLE SystemMetricSnapshot (
    id                 BINARY(16)     PRIMARY KEY,
    capturedAt         DATETIME(6)    NOT NULL,

    -- CPU 사용률(%)
    cpuUserPct         DECIMAL(5,2)   NULL,
    cpuSystemPct       DECIMAL(5,2)   NULL,
    cpuIoWaitPct       DECIMAL(5,2)   NULL,

    -- 메모리 / Swap
    memUsedPct         DECIMAL(5,2)   NULL,
    memUsedBytes       BIGINT         NULL,
    memTotalBytes      BIGINT         NULL,
    swapUsedBytes      BIGINT         NULL,
    swapTotalBytes     BIGINT         NULL,

    -- 디스크(파일시스템 합산 + 물리 디스크 IOPS)
    diskUsedPct        DECIMAL(5,2)   NULL,
    diskFreeBytes      BIGINT         NULL,
    diskTotalBytes     BIGINT         NULL,
    diskReadsPerSec    DECIMAL(12,2)  NULL,
    diskWritesPerSec   DECIMAL(12,2)  NULL,

    -- 네트워크(전체 인터페이스 합산)
    netInBytesPerSec   DECIMAL(16,2)  NULL,
    netOutBytesPerSec  DECIMAL(16,2)  NULL,
    tcpConnEstablished INT            NULL,

    KEY ixSystemMetricCapturedAt (capturedAt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
