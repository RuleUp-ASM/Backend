-- 경로: src/main/resources/db/migration/V6__create_recommendation_tables.sql
-- 추천 warm-up(콜드스타트) 점수. 세그먼트(국가/성별/연령대)별 템플릿 인기 점수.
--   · 선택(챌린지 생성) 시 해당 세그먼트 score↑, 조회 시 세그먼트 합으로 정규화 → 나머지 상대↓
--   · COUNTRY를 콜드스타트 base로(GLOBAL 미사용)
--   · 개인화(warm-up 이후)는 별도 테이블 없이 Challenge/ChallengeMember/VerificationMethodResult에서 파생
--   · templateId는 Challenge와 동일하게 FK 없이 앱 검증(정적 카탈로그)

CREATE TABLE TemplateSegmentScore (
                                      segmentType    ENUM('COUNTRY','GENDER','AGE_BAND') NOT NULL,
                                      segmentValue   VARCHAR(20)     NOT NULL,                      -- 'KR' / 'MALE' / '20s' 등
                                      templateId     BIGINT UNSIGNED NOT NULL,
                                      score          DECIMAL(12,4)   NOT NULL DEFAULT 0,            -- 누적 가중 점수
                                      selectionCount INT             NOT NULL DEFAULT 0,            -- 선택 횟수(정규화·디버깅용)
                                      updatedAt      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

                                      PRIMARY KEY (segmentType, segmentValue, templateId)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;