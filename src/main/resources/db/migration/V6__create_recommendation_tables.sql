-- 경로: src/main/resources/db/migration/V6__create_recommendation_tables.sql
-- 추천 warm-up(콜드스타트) 점수. 세그먼트(전체/국가/성별/연령대/플랫폼)별 템플릿 인기 점수.
--   · 점수는 SegmentScoreBatch가 주기적으로 누적 챌린지를 재집계해 통째로 재작성(실시간 X)
--   · GLOBAL("ALL")은 모든 유저 공통 base → 인구통계가 없어도 전체 인기도로 추천
--   · PLATFORM은 가입·로그인 시 수집한 기기 플랫폼(ANDROID/IOS)
--   · 개인화(warm-up 이후)는 별도 테이블 없이 Challenge/ChallengeMember/VerificationMethodResult에서 파생
--   · templateId는 Challenge와 동일하게 FK 없이 앱 검증(정적 카탈로그)

CREATE TABLE TemplateSegmentScore (
                                      segmentType    ENUM('GLOBAL','COUNTRY','GENDER','AGE_BAND','PLATFORM') NOT NULL,
                                      segmentValue   VARCHAR(20)     NOT NULL,                      -- 'ALL' / 'KR' / 'MALE' / '20s' / 'ANDROID' 등
                                      templateId     BIGINT UNSIGNED NOT NULL,
                                      score          DECIMAL(12,4)   NOT NULL DEFAULT 0,            -- 누적 가중 점수
                                      selectionCount INT             NOT NULL DEFAULT 0,            -- 선택 횟수(정규화·디버깅용)
                                      updatedAt      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

                                      PRIMARY KEY (segmentType, segmentValue, templateId)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;