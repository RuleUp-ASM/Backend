-- 경로: src/main/resources/db/migration/V21__challenge_explore.sql
-- 챌린지 탐색(홈 인기 + 카테고리 + 목록 정렬)용 역정규화 컬럼·집계 테이블.
--   · 값은 질의 시점 집계 없이 배치가 유지(신선도 SLA 10분~1시간, 탐색 스펙 §4).
--   · trendingScore : 10분 배치가 최근 24h 참여의 지수감쇠 합으로 갱신(§2.1).
--   · failCount     : 방 확정 실패 인원(성공/실패 비율 §3.2.4). 배치가 멤버 집계로 갱신.
--   · verificationType : 정렬/필터를 위해 verificationConfig(JSON)의 selectedMethod 를 컬럼으로 승격(AUTO/MANUAL).

ALTER TABLE Challenge
    ADD COLUMN trendingScore    DOUBLE      NOT NULL DEFAULT 0   AFTER participantCount,
    ADD COLUMN failCount        INT         NOT NULL DEFAULT 0   AFTER trendingScore,
    ADD COLUMN verificationType VARCHAR(10) NULL                 AFTER failCount;

-- 기존 행 백필: verificationConfig JSON 의 selectedMethod(AUTO/MANUAL)를 컬럼으로 복사.
UPDATE Challenge
SET verificationType = JSON_UNQUOTE(JSON_EXTRACT(verificationConfig, '$.selectedMethod'))
WHERE verificationType IS NULL AND verificationConfig IS NOT NULL;

-- 탐색 목록 정렬·필터 보조 인덱스(종료 안 된 공개 챌린지 스캔).
CREATE INDEX idx_challenge_explore ON Challenge (deletedAt, moderationStatus, status, endDate);
CREATE INDEX idx_challenge_template ON Challenge (templateId);

-- 템플릿 단위 집계(완주율·사용자 수). 정적 카탈로그(RoutineTemplate)와 1:1, FK 없이 앱 검증.
--   · usageCount            : 파생된 모든 챌린지의 현재 참여자 수 합(§3.2.1).
--   · completedParticipants : 완료된 회차의 누적 참여자 수(표본, >10 일 때만 completionRate 노출, §3.2.3).
--   · completionRate        : 완주자 / 완료 참여자 (0~1, 표본 부족 시 NULL).
CREATE TABLE TemplateStats (
                               templateId            BIGINT        NOT NULL,
                               usageCount            BIGINT        NOT NULL DEFAULT 0,
                               completedParticipants BIGINT        NOT NULL DEFAULT 0,
                               completionRate        DECIMAL(5,4)  NULL,
                               updatedAt             DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

                               PRIMARY KEY (templateId)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
