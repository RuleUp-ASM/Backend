-- 경로: src/main/resources/db/migration/V20__segment_type_weight.sql
-- 추천 특성 가중치 학습(§8.1). 세그먼트 축(type)별 1행(총 5행)짜리 초경량 테이블.
--   · "어느 특성이 취향을 잘 가르는지"를 배치가 매일 04:00 KST에 실제 선택 데이터로 재계산해
--     (JSD 기반 구별력 + shrinkage + clamp) 점수와 같은 트랜잭션에서 재작성.
--   · 조회는 w(type) × 저장점수 로 곱한다. GLOBAL 은 학습하지 않고 0.3 고정.
--   · 행이 없으면 조회는 prior(COUNTRY/GENDER 1.0, AGE_BAND 1.2, PLATFORM 0.5, GLOBAL 0.3)로 폴백 → 시드 불필요.
CREATE TABLE SegmentTypeWeight (
                                   segmentType ENUM('GLOBAL','COUNTRY','GENDER','AGE_BAND','PLATFORM') NOT NULL,
                                   weight      DECIMAL(6,4) NOT NULL DEFAULT 1.0000,   -- clamp [0.2, 2.0]
                                   sampleSize  BIGINT       NOT NULL DEFAULT 0,        -- 학습 표본(그 축 총 선택 수)
                                   updatedAt   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

                                   PRIMARY KEY (segmentType)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
