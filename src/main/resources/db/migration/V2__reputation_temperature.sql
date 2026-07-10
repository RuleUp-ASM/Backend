-- 경로: src/main/resources/db/migration/V2__reputation_temperature.sql
-- 매너 온도 계산(온도 계산 테크스펙 V1) 상태 컬럼. 일일 배치가 갱신한다.
--   · volumeIndex(V)        : "지금 몇 개를 얼마나 잘"의 지수이동평균(시간상수 150일).
--   · tenureBonus(B)        : "몇 년째 잘 지키는가" 연차 보너스(첫 1년 이후 +1/년, 상한 6).
--   · qualifyingDays        : 누적 자격일(s_d ≥ 0.6인 날). B 적립 게이트(1년=365일).
--   · lastQualifyingDate    : 마지막 자격일(7일 유예·연속성 판정 기준).
--   · lastCalculatedDate    : 배치 멱등 가드(하루 1스텝, 이미 계산한 날 재계산 방지).
-- mannerTemperature 는 밴드 매핑 T=f(V+B) 결과. 정밀도를 스펙(DECIMAL(5,2))에 맞춰 넓힌다.
ALTER TABLE ReputationScore
    MODIFY COLUMN mannerTemperature  DECIMAL(5,2) NOT NULL DEFAULT 36.50,
    ADD COLUMN volumeIndex        DECIMAL(7,4) NOT NULL DEFAULT 0 AFTER mannerTemperature,
    ADD COLUMN tenureBonus        DECIMAL(6,4) NOT NULL DEFAULT 0 AFTER volumeIndex,
    ADD COLUMN qualifyingDays     INT          NOT NULL DEFAULT 0 AFTER tenureBonus,
    ADD COLUMN lastQualifyingDate DATE         NULL              AFTER qualifyingDays,
    ADD COLUMN lastCalculatedDate DATE         NULL              AFTER lastQualifyingDate;

-- 배치 대상 스캔(아직 오늘 계산 안 된 유저).
CREATE INDEX ixReputationCalcDate ON ReputationScore (lastCalculatedDate);
