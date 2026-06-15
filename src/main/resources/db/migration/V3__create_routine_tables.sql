-- 경로: src/main/resources/db/migration/V3__create_routine_tables.sql
-- 루틴 템플릿 카탈로그 (개발자가 미리 정의하는 인증 가능한 루틴들. 챌린지 생성 시 매칭 대상).
-- V1/V2 컨벤션 유지:
--   · routine_template 은 정적 카탈로그라 BIGINT auto_increment 사용(원본 스키마 그대로).
--   · 챌린지가 이 템플릿을 매칭해 template_id + 인증 스냅샷 + params 를 들고 간다(challenges 테이블).
--   · 배열/맵 설정은 JSON, 값 검증은 앱에서(신뢰 경계: LLM/유저 입력은 서버가 재검증).

-- ===== routine_template : 루틴 지식베이스(매칭 대상) =====
CREATE TABLE routine_template (
                                  id                        BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                                  name                      VARCHAR(100)    NOT NULL,
                                  description               VARCHAR(255)    NULL,
                                  category                  ENUM('EXERCISE','READING','MEDITATION','HEALTH','WAKEUP',
                                   'WORK','STUDY','HOBBY','COOKING','FINANCE',
                                   'ENVIRONMENT','RELATIONSHIP','MUSIC','WRITING','CODING') NOT NULL,

    -- 자동 인증 옵션(자동 불가 루틴은 전부 NULL)
                                  auto_verification_type    ENUM('PHONE','HEALTH_CONNECT','EXTERNAL') NULL,
                                  auto_signal_source        ENUM('GEOFENCE','GPS','ACTIVITY','SLEEP','USAGE',
                                   'APP_FEATURE','HC_RECORD','EXTERNAL_API') NULL,
                                  auto_wearable_req         ENUM('NONE','OPTIONAL','REQUIRED') NULL,
                                  auto_external_service     VARCHAR(40)     NULL,
                                  auto_required_permissions JSON            NULL,   -- ["ACCESS_FINE_LOCATION", ...]

    -- 수동 인증 옵션(항상 존재)
                                  manual_signal_source      ENUM('PHOTO','GROUP_CHECK') NOT NULL DEFAULT 'PHOTO',

    -- 추천 기본값(생성 컬럼 = 단일 원천)
                                  has_auto       BOOLEAN AS (auto_verification_type IS NOT NULL) STORED,
                                  default_method ENUM('AUTO','MANUAL')
                   AS (IF(auto_verification_type IS NOT NULL,'AUTO','MANUAL')) STORED,

    -- 목표 파라미터(거리/시간/횟수 등). 예: {"distance_km":{"default":3,"unit":"km","min":1,"max":50}}
    -- min/max 는 선택(있으면 서버가 범위 검증, 없으면 양수만 확인).
                                  param_schema   JSON NULL,

                                  rationale      VARCHAR(255) NULL,
                                  created_at     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                                  updated_at     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

                                  PRIMARY KEY (id),
                                  KEY idx_routine_template_category (category),
                                  KEY idx_routine_template_has_auto (has_auto),
                                  FULLTEXT KEY ftx_name_desc (name, description) WITH PARSER ngram
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ===== 시드: routine_template 105개 =====
INSERT INTO routine_template
(name, description, category,
 auto_verification_type, auto_signal_source, auto_wearable_req,
 auto_external_service, auto_required_permissions,
 manual_signal_source, param_schema, rationale)
VALUES
-- ===== 🏃 EXERCISE (1-7) =====
('헬스장 가서 1시간 운동', NULL, 'EXERCISE', 'PHONE','GEOFENCE','NONE', NULL, '["ACCESS_FINE_LOCATION","ACCESS_BACKGROUND_LOCATION"]', 'PHOTO', '{"duration_min":{"default":60,"unit":"min"}}', '장소 체류 시간 감지'),
('아침 3km 달리기', NULL, 'EXERCISE', 'PHONE','GPS','OPTIONAL', NULL, '["ACCESS_FINE_LOCATION","ACCESS_BACKGROUND_LOCATION"]', 'PHOTO', '{"distance_km":{"default":3,"unit":"km"},"duration_min":{"default":null,"unit":"min"}}', '연속 측위 누적거리'),
('하루 8,000보 걷기', NULL, 'EXERCISE', 'HEALTH_CONNECT','HC_RECORD','OPTIONAL', NULL, '["android.permission.health.READ_STEPS"]', 'PHOTO', '{"steps":{"default":8000,"unit":"steps"}}', 'HC 걸음수 집계 — 폰 보행센서 미사용'),
('홈트 30분', '영상 따라하기', 'EXERCISE', NULL,NULL,NULL,NULL,NULL, 'PHOTO', '{"duration_min":{"default":30,"unit":"min"}}', '실내 맨몸 — 폰 신호 없음'),
('주 3회 수영장 가기', NULL, 'EXERCISE', 'PHONE','GEOFENCE','NONE', NULL, '["ACCESS_FINE_LOCATION","ACCESS_BACKGROUND_LOCATION"]', 'PHOTO', '{"times_per_week":{"default":3}}', '장소 방문 감지'),
('자전거로 출퇴근', NULL, 'EXERCISE', 'PHONE','ACTIVITY','NONE', NULL, '["ACTIVITY_RECOGNITION"]', 'PHOTO', NULL, 'ON_BICYCLE 전환 감지'),
('운동 후 스트레칭 10분', NULL, 'EXERCISE', NULL,NULL,NULL,NULL,NULL, 'PHOTO', '{"duration_min":{"default":10,"unit":"min"}}', '행위 신호 없음'),

-- ===== 📚 READING (8-14) =====
('e북 앱 30분 읽기', NULL, 'READING', 'PHONE','USAGE','NONE', NULL, '["PACKAGE_USAGE_STATS"]', 'PHOTO', '{"duration_min":{"default":30,"unit":"min"}}', '대상 앱 사용시간'),
('종이책 30분 읽기', NULL, 'READING', NULL,NULL,NULL,NULL,NULL, 'PHOTO', '{"duration_min":{"default":30,"unit":"min"}}', '오프라인 — 신호 없음'),
('주말 도서관 가기', NULL, 'READING', 'PHONE','GEOFENCE','NONE', NULL, '["ACCESS_FINE_LOCATION","ACCESS_BACKGROUND_LOCATION"]', 'PHOTO', NULL, '장소 방문 감지'),
('자기 전 10페이지 읽기', NULL, 'READING', NULL,NULL,NULL,NULL,NULL, 'PHOTO', '{"pages":{"default":10}}', '오프라인 — 신호 없음'),
('출근길 오디오북 20분', NULL, 'READING', 'PHONE','USAGE','NONE', NULL, '["PACKAGE_USAGE_STATS"]', 'PHOTO', '{"duration_min":{"default":20,"unit":"min"}}', '대상 앱 사용시간'),
('한 줄 독서 기록 남기기', NULL, 'READING', 'PHONE','APP_FEATURE','NONE', NULL, '[]', 'GROUP_CHECK', NULL, '인앱 작성이 증거'),
('한 달에 책 2권 완독', NULL, 'READING', NULL,NULL,NULL,NULL,NULL, 'PHOTO', '{"books_per_month":{"default":2}}', '완독 측정 불가'),

-- ===== 🧘 MEDITATION (15-21) =====
('아침 명상 앱 10분', NULL, 'MEDITATION', 'PHONE','USAGE','NONE', NULL, '["PACKAGE_USAGE_STATS"]', 'PHOTO', '{"duration_min":{"default":10,"unit":"min"}}', '대상 앱 사용시간'),
('자기 전 호흡 명상 5분', NULL, 'MEDITATION', 'PHONE','USAGE','NONE', NULL, '["PACKAGE_USAGE_STATS"]', 'PHOTO', '{"duration_min":{"default":5,"unit":"min"}}', '앱 사용시간 + 시간대'),
('요가원·명상센터 가기', NULL, 'MEDITATION', 'PHONE','GEOFENCE','NONE', NULL, '["ACCESS_FINE_LOCATION","ACCESS_BACKGROUND_LOCATION"]', 'PHOTO', NULL, '장소 방문 감지'),
('워치 마음챙김 세션 기록', NULL, 'MEDITATION', 'HEALTH_CONNECT','HC_RECORD','REQUIRED', NULL, '["android.permission.health.READ_MINDFULNESS"]', 'PHOTO', '{"duration_min":{"default":10,"unit":"min"}}', 'MindfulnessSessionRecord — 워치 필수'),
('폰 없는 15분 (화면 OFF)', NULL, 'MEDITATION', 'PHONE','USAGE','NONE', NULL, '["PACKAGE_USAGE_STATS"]', 'GROUP_CHECK', '{"duration_min":{"default":15,"unit":"min"}}', '화면 OFF 유지 감지'),
('명상 일지 쓰기', NULL, 'MEDITATION', 'PHONE','APP_FEATURE','NONE', NULL, '[]', 'GROUP_CHECK', NULL, '인앱 작성이 증거'),
('명상 공간 인증샷', NULL, 'MEDITATION', NULL,NULL,NULL,NULL,NULL, 'PHOTO', NULL, '행위 신호 없음'),

-- ===== 💧 HEALTH (22-28) =====
('하루 물 2L 마시기', NULL, 'HEALTH', NULL,NULL,NULL,NULL,NULL, 'GROUP_CHECK', '{"volume_l":{"default":2,"unit":"L"}}', 'HC 음수는 손입력뿐 — 자동 신호 없음'),
('영양제 챙겨 먹기', NULL, 'HEALTH', NULL,NULL,NULL,NULL,NULL, 'PHOTO', NULL, '행위 신호 없음'),
('12시 전에 잠들기', NULL, 'HEALTH', 'PHONE','USAGE','NONE', NULL, '["PACKAGE_USAGE_STATS"]', 'GROUP_CHECK', '{"target_time":{"default":"00:00","unit":"hh:mm"}}', '야간 마지막 화면 OFF 시각'),
('7시간 이상 수면', NULL, 'HEALTH', 'PHONE','SLEEP','OPTIONAL', NULL, '["ACTIVITY_RECOGNITION"]', 'PHOTO', '{"sleep_hours":{"default":7,"unit":"h"}}', 'Sleep API 수면 구간 (폰 단독)'),
('하루 10,000보', NULL, 'HEALTH', 'HEALTH_CONNECT','HC_RECORD','OPTIONAL', NULL, '["android.permission.health.READ_STEPS"]', 'PHOTO', '{"steps":{"default":10000,"unit":"steps"}}', 'HC 걸음수 집계'),
('주 1회 체중 기록', NULL, 'HEALTH', 'HEALTH_CONNECT','HC_RECORD','NONE', NULL, '["android.permission.health.READ_WEIGHT"]', 'PHOTO', '{"times_per_week":{"default":1}}', 'WeightRecord — 스마트체중계 한정 자동'),
('점심 후 10분 산책', NULL, 'HEALTH', 'PHONE','ACTIVITY','OPTIONAL', NULL, '["ACTIVITY_RECOGNITION"]', 'PHOTO', '{"duration_min":{"default":10,"unit":"min"}}', 'WALKING 전환 세션 (보조신호)'),

-- ===== 🌅 WAKEUP (29-35) =====
('아침 7시 전에 일어나기', NULL, 'WAKEUP', 'PHONE','USAGE','NONE', NULL, '["PACKAGE_USAGE_STATS"]', 'GROUP_CHECK', '{"target_time":{"default":"07:00","unit":"hh:mm"}}', '당일 첫 KEYGUARD_HIDDEN'),
('기상 후 1시간 폰 금지', NULL, 'WAKEUP', 'PHONE','USAGE','NONE', NULL, '["PACKAGE_USAGE_STATS"]', 'GROUP_CHECK', '{"duration_min":{"default":60,"unit":"min"}}', '시간대 앱 사용 0'),
('미라클모닝 책상 인증샷', NULL, 'WAKEUP', NULL,NULL,NULL,NULL,NULL, 'PHOTO', NULL, '행위는 사진'),
('기상 직후 물 한 잔', NULL, 'WAKEUP', NULL,NULL,NULL,NULL,NULL, 'PHOTO', NULL, '행위 신호 없음'),
('알람 한 번에 끄고 미션 수행', NULL, 'WAKEUP', 'PHONE','APP_FEATURE','NONE', NULL, '[]', 'GROUP_CHECK', NULL, '인앱 알람 해제가 증거'),
('주말에도 8시 전 기상', NULL, 'WAKEUP', 'PHONE','USAGE','NONE', NULL, '["PACKAGE_USAGE_STATS"]', 'GROUP_CHECK', '{"target_time":{"default":"08:00","unit":"hh:mm"}}', '첫 잠금해제 시각 (요일 조건)'),
('기상 후 이불 정리', NULL, 'WAKEUP', NULL,NULL,NULL,NULL,NULL, 'PHOTO', NULL, '행위 신호 없음'),

-- ===== 💼 WORK (36-42) =====
('9시 전 사무실 도착', NULL, 'WORK', 'PHONE','GEOFENCE','NONE', NULL, '["ACCESS_FINE_LOCATION","ACCESS_BACKGROUND_LOCATION"]', 'GROUP_CHECK', '{"target_time":{"default":"09:00","unit":"hh:mm"}}', '장소 도착 시각'),
('오전 딥워크 2시간 (SNS 금지)', NULL, 'WORK', 'PHONE','USAGE','NONE', NULL, '["PACKAGE_USAGE_STATS"]', 'GROUP_CHECK', '{"duration_min":{"default":120,"unit":"min"}}', '시간대 SNS 사용 측정'),
('업무 시작 전 투두리스트 작성', NULL, 'WORK', 'PHONE','APP_FEATURE','NONE', NULL, '[]', 'GROUP_CHECK', NULL, '인앱 작성이 증거'),
('점심 후 카페에서 30분 집중', NULL, 'WORK', 'PHONE','GEOFENCE','NONE', NULL, '["ACCESS_FINE_LOCATION","ACCESS_BACKGROUND_LOCATION"]', 'PHOTO', '{"duration_min":{"default":30,"unit":"min"}}', '장소 체류 — 등록 장소 한정'),
('퇴근 후 업무 메신저 안 보기', NULL, 'WORK', 'PHONE','USAGE','NONE', NULL, '["PACKAGE_USAGE_STATS"]', 'GROUP_CHECK', NULL, '시간대 대상 앱 사용'),
('금요일 주간 회고 작성', NULL, 'WORK', 'PHONE','APP_FEATURE','NONE', NULL, '[]', 'GROUP_CHECK', NULL, '인앱 작성이 증거'),
('퇴근 전 책상 정리', NULL, 'WORK', NULL,NULL,NULL,NULL,NULL, 'PHOTO', NULL, '행위 신호 없음'),

-- ===== 📖 STUDY (43-49) =====
('독서실 3시간 공부', NULL, 'STUDY', 'PHONE','GEOFENCE','NONE', NULL, '["ACCESS_FINE_LOCATION","ACCESS_BACKGROUND_LOCATION"]', 'PHOTO', '{"duration_min":{"default":180,"unit":"min"}}', '장소 체류 시간'),
('인강 1시간 듣기', NULL, 'STUDY', 'PHONE','USAGE','NONE', NULL, '["PACKAGE_USAGE_STATS"]', 'PHOTO', '{"duration_min":{"default":60,"unit":"min"}}', '대상 앱 사용시간'),
('공부 시간대 폰 금지 (19~22시)', NULL, 'STUDY', 'PHONE','USAGE','NONE', NULL, '["PACKAGE_USAGE_STATS"]', 'GROUP_CHECK', '{"start_time":{"default":"19:00"},"end_time":{"default":"22:00"}}', '시간대 앱 사용'),
('암기 앱 20분', NULL, 'STUDY', 'PHONE','USAGE','NONE', NULL, '["PACKAGE_USAGE_STATS"]', 'PHOTO', '{"duration_min":{"default":20,"unit":"min"}}', '대상 앱 사용시간'),
('오답노트 정리', NULL, 'STUDY', NULL,NULL,NULL,NULL,NULL, 'PHOTO', NULL, '오프라인 — 신호 없음'),
('도서관 21시까지 공부', NULL, 'STUDY', 'PHONE','GEOFENCE','NONE', NULL, '["ACCESS_FINE_LOCATION","ACCESS_BACKGROUND_LOCATION"]', 'GROUP_CHECK', '{"target_time":{"default":"21:00","unit":"hh:mm"}}', '장소 이탈 시각'),
('스터디 모임 참석', NULL, 'STUDY', 'PHONE','GEOFENCE','NONE', NULL, '["ACCESS_FINE_LOCATION","ACCESS_BACKGROUND_LOCATION"]', 'PHOTO', NULL, '장소 방문'),

-- ===== 🎨 HOBBY (50-56) =====
('드로잉 앱 30분 그리기', NULL, 'HOBBY', 'PHONE','USAGE','NONE', NULL, '["PACKAGE_USAGE_STATS"]', 'PHOTO', '{"duration_min":{"default":30,"unit":"min"}}', '대상 앱 사용시간'),
('그림 한 장 완성 인증', NULL, 'HOBBY', NULL,NULL,NULL,NULL,NULL, 'PHOTO', NULL, '결과물만 존재'),
('클라이밍장 가기', NULL, 'HOBBY', 'PHONE','GEOFENCE','NONE', NULL, '["ACCESS_FINE_LOCATION","ACCESS_BACKGROUND_LOCATION"]', 'PHOTO', NULL, '장소 방문'),
('사진 산책 (출사) 1시간', NULL, 'HOBBY', NULL,NULL,NULL,NULL,NULL, 'PHOTO', '{"duration_min":{"default":60,"unit":"min"}}', '결과물 사진이 곧 인증'),
('뜨개질·공예 30분', NULL, 'HOBBY', NULL,NULL,NULL,NULL,NULL, 'PHOTO', '{"duration_min":{"default":30,"unit":"min"}}', '행위 신호 없음'),
('공방 수업 참석', NULL, 'HOBBY', 'PHONE','GEOFENCE','NONE', NULL, '["ACCESS_FINE_LOCATION","ACCESS_BACKGROUND_LOCATION"]', 'PHOTO', NULL, '장소 방문'),
('퍼즐·레고 30분 조립', NULL, 'HOBBY', NULL,NULL,NULL,NULL,NULL, 'PHOTO', '{"duration_min":{"default":30,"unit":"min"}}', '행위 신호 없음'),

-- ===== 🍳 COOKING (57-63) =====
('아침 직접 차려 먹기', NULL, 'COOKING', NULL,NULL,NULL,NULL,NULL, 'PHOTO', NULL, '요리·식사 신호 없음'),
('주 3회 도시락 싸기', NULL, 'COOKING', NULL,NULL,NULL,NULL,NULL, 'PHOTO', '{"times_per_week":{"default":3}}', '요리 신호 없음'),
('배달 대신 집밥', NULL, 'COOKING', NULL,NULL,NULL,NULL,NULL, 'PHOTO', NULL, '배달앱 0분(앱사용 보조) + 집밥 사진'),
('주말 새 레시피 도전', NULL, 'COOKING', NULL,NULL,NULL,NULL,NULL, 'PHOTO', NULL, '요리 신호 없음'),
('세 끼 식단 기록', NULL, 'COOKING', NULL,NULL,NULL,NULL,NULL, 'PHOTO', NULL, '식사 신호 없음'),
('주 1회 장보기', NULL, 'COOKING', 'PHONE','GEOFENCE','NONE', NULL, '["ACCESS_FINE_LOCATION","ACCESS_BACKGROUND_LOCATION"]', 'PHOTO', '{"times_per_week":{"default":1}}', '마트 방문 감지'),
('식사 후 바로 설거지', NULL, 'COOKING', NULL,NULL,NULL,NULL,NULL, 'PHOTO', NULL, '행위 신호 없음'),

-- ===== 💰 FINANCE (64-70) =====
('가계부 앱 5분 작성', NULL, 'FINANCE', 'PHONE','USAGE','NONE', NULL, '["PACKAGE_USAGE_STATS"]', 'PHOTO', '{"duration_min":{"default":5,"unit":"min"}}', '앱 사용시간 (켜놓기 치팅 여지)'),
('주 2회 무지출 데이', NULL, 'FINANCE', NULL,NULL,NULL,NULL,NULL, 'GROUP_CHECK', '{"times_per_week":{"default":2}}', '부재(안 샀음)는 증명 불가'),
('경제 뉴스 15분 읽기', NULL, 'FINANCE', 'PHONE','USAGE','NONE', NULL, '["PACKAGE_USAGE_STATS"]', 'PHOTO', '{"duration_min":{"default":15,"unit":"min"}}', '대상 앱 사용시간'),
('저녁 소비 회고 쓰기', NULL, 'FINANCE', 'PHONE','APP_FEATURE','NONE', NULL, '[]', 'GROUP_CHECK', NULL, '인앱 작성이 증거'),
('재테크 책 30분', NULL, 'FINANCE', NULL,NULL,NULL,NULL,NULL, 'PHOTO', '{"duration_min":{"default":30,"unit":"min"}}', '오프라인 독서'),
('쇼핑앱 하루 30분 이하', NULL, 'FINANCE', 'PHONE','USAGE','NONE', NULL, '["PACKAGE_USAGE_STATS"]', 'GROUP_CHECK', '{"max_minutes":{"default":30,"unit":"min"}}', '대상 앱 사용 상한'),
('주식앱 하루 10분 이하', NULL, 'FINANCE', 'PHONE','USAGE','NONE', NULL, '["PACKAGE_USAGE_STATS"]', 'GROUP_CHECK', '{"max_minutes":{"default":10,"unit":"min"}}', '대상 앱 사용 상한'),

-- ===== 🌱 ENVIRONMENT (71-77) =====
('텀블러 사용하기', NULL, 'ENVIRONMENT', NULL,NULL,NULL,NULL,NULL, 'PHOTO', NULL, '행위 신호 없음'),
('플로깅 30분', NULL, 'ENVIRONMENT', NULL,NULL,NULL,NULL,NULL, 'PHOTO', '{"duration_min":{"default":30,"unit":"min"}}', '줍는 행위 신호 없음'),
('장바구니 들고 장보기', NULL, 'ENVIRONMENT', NULL,NULL,NULL,NULL,NULL, 'PHOTO', NULL, '행위 신호 없음'),
('분리수거 하기', NULL, 'ENVIRONMENT', NULL,NULL,NULL,NULL,NULL, 'PHOTO', NULL, '행위 신호 없음'),
('도보 출근 (차 대신)', NULL, 'ENVIRONMENT', 'PHONE','ACTIVITY','OPTIONAL', NULL, '["ACTIVITY_RECOGNITION","ACCESS_FINE_LOCATION"]', 'PHOTO', NULL, 'WALKING 전환 + 도착 감지'),
('다회용기 포장 주문', NULL, 'ENVIRONMENT', NULL,NULL,NULL,NULL,NULL, 'PHOTO', NULL, '행위 신호 없음'),
('잔반 없는 식사 (빈 그릇)', NULL, 'ENVIRONMENT', NULL,NULL,NULL,NULL,NULL, 'PHOTO', NULL, '식사 결과 신호 없음'),

-- ===== 🤝 RELATIONSHIP (78-84) =====
('부모님께 주 2회 안부 전화', NULL, 'RELATIONSHIP', NULL,NULL,NULL,NULL,NULL, 'GROUP_CHECK', '{"times_per_week":{"default":2}}', '통화기록 접근 정책상 차단'),
('가족 저녁 함께 먹기', NULL, 'RELATIONSHIP', NULL,NULL,NULL,NULL,NULL, 'PHOTO', NULL, '함께함 측정 불가'),
('친구 약속 참석', NULL, 'RELATIONSHIP', 'PHONE','GEOFENCE','NONE', NULL, '["ACCESS_FINE_LOCATION","ACCESS_BACKGROUND_LOCATION"]', 'PHOTO', NULL, '장소 방문 — 챌린지별 위치 핀 전제'),
('월 1회 본가 방문', NULL, 'RELATIONSHIP', 'PHONE','GEOFENCE','NONE', NULL, '["ACCESS_FINE_LOCATION","ACCESS_BACKGROUND_LOCATION"]', 'PHOTO', '{"times_per_month":{"default":1}}', '장소 방문'),
('하루 한 번 칭찬·감사 표현', NULL, 'RELATIONSHIP', NULL,NULL,NULL,NULL,NULL, 'GROUP_CHECK', '{"times_per_day":{"default":1}}', '사회적 행위 — 신호 없음'),
('동호회 정기모임 출석', NULL, 'RELATIONSHIP', 'PHONE','GEOFENCE','NONE', NULL, '["ACCESS_FINE_LOCATION","ACCESS_BACKGROUND_LOCATION"]', 'PHOTO', NULL, '장소 방문'),
('주 1회 손편지·장문 편지', NULL, 'RELATIONSHIP', NULL,NULL,NULL,NULL,NULL, 'PHOTO', '{"times_per_week":{"default":1}}', '오프라인 — 신호 없음'),

-- ===== 🎵 MUSIC (85-91) =====
('피아노 연습 30분', NULL, 'MUSIC', NULL,NULL,NULL,NULL,NULL, 'PHOTO', '{"duration_min":{"default":30,"unit":"min"}}', '연주(소리) 폰 신호 없음'),
('악기 학습 앱 20분', NULL, 'MUSIC', 'PHONE','USAGE','NONE', NULL, '["PACKAGE_USAGE_STATS"]', 'PHOTO', '{"duration_min":{"default":20,"unit":"min"}}', '대상 앱 사용시간'),
('연습실 가기', NULL, 'MUSIC', 'PHONE','GEOFENCE','NONE', NULL, '["ACCESS_FINE_LOCATION","ACCESS_BACKGROUND_LOCATION"]', 'PHOTO', NULL, '장소 방문'),
('보컬 연습 인증', NULL, 'MUSIC', NULL,NULL,NULL,NULL,NULL, 'PHOTO', NULL, '오디오 분석 범위 밖'),
('새 곡 한 곡 완주 연습', NULL, 'MUSIC', NULL,NULL,NULL,NULL,NULL, 'PHOTO', NULL, '연주 신호 없음'),
('합주·밴드 연습 참석', NULL, 'MUSIC', 'PHONE','GEOFENCE','NONE', NULL, '["ACCESS_FINE_LOCATION","ACCESS_BACKGROUND_LOCATION"]', 'PHOTO', NULL, '장소 방문'),
('음악 이론 앱 15분', NULL, 'MUSIC', 'PHONE','USAGE','NONE', NULL, '["PACKAGE_USAGE_STATS"]', 'PHOTO', '{"duration_min":{"default":15,"unit":"min"}}', '대상 앱 사용시간'),

-- ===== ✍️ WRITING (92-98) =====
('하루 일기 쓰기', NULL, 'WRITING', 'PHONE','APP_FEATURE','NONE', NULL, '[]', 'GROUP_CHECK', NULL, '인앱 작성 + 글자 수 검증'),
('모닝 페이지 (아침 글쓰기)', NULL, 'WRITING', 'PHONE','APP_FEATURE','NONE', NULL, '[]', 'GROUP_CHECK', NULL, '인앱 작성 + 시간대 검증'),
('블로그 주 1회 발행', NULL, 'WRITING', 'EXTERNAL','EXTERNAL_API','NONE', 'RSS', '[]', 'PHOTO', '{"times_per_week":{"default":1}}', '발행 글 RSS 확인'),
('감사일기 3줄', NULL, 'WRITING', 'PHONE','APP_FEATURE','NONE', NULL, '[]', 'GROUP_CHECK', '{"lines":{"default":3}}', '인앱 작성이 증거'),
('하루 500자 이상 글쓰기', NULL, 'WRITING', 'PHONE','APP_FEATURE','NONE', NULL, '[]', 'GROUP_CHECK', '{"min_chars":{"default":500}}', '인앱 글자 수 검증'),
('필사 한 페이지', NULL, 'WRITING', NULL,NULL,NULL,NULL,NULL, 'PHOTO', '{"pages":{"default":1}}', '손글씨 — 오프라인'),
('독서 노트 정리', NULL, 'WRITING', NULL,NULL,NULL,NULL,NULL, 'PHOTO', NULL, '오프라인 — 신호 없음'),

-- ===== 💻 CODING (99-105) =====
('1일 1커밋', NULL, 'CODING', 'EXTERNAL','EXTERNAL_API','NONE', 'GitHub', '[]', 'PHOTO', '{"commits_per_day":{"default":1}}', 'GitHub 기여 내역 조회'),
('코드포스 1일 1문제', '구 백준 1일 1솔 대체 (BOJ 2026-04 종료·부활 불확실)', 'CODING', 'EXTERNAL','EXTERNAL_API','NONE', 'Codeforces', '[]', 'PHOTO', '{"problems_per_day":{"default":1}}', 'Codeforces user.status 공개 API. 프로그래머스 택 시 수동(API 없음)'),
('알고리즘 스터디 참석', NULL, 'CODING', 'PHONE','GEOFENCE','NONE', NULL, '["ACCESS_FINE_LOCATION","ACCESS_BACKGROUND_LOCATION"]', 'PHOTO', NULL, '장소 방문'),
('CS 인강 30분', NULL, 'CODING', 'PHONE','USAGE','NONE', NULL, '["PACKAGE_USAGE_STATS"]', 'PHOTO', '{"duration_min":{"default":30,"unit":"min"}}', '대상 앱 사용시간'),
('사이드 프로젝트 1시간', NULL, 'CODING', 'EXTERNAL','EXTERNAL_API','NONE', 'WakaTime', '[]', 'PHOTO', '{"duration_min":{"default":60,"unit":"min"}}', 'WakaTime/GitHub 코딩시간 (PC라 폰 신호 없음)'),
('기술 블로그 주 1회', NULL, 'CODING', 'EXTERNAL','EXTERNAL_API','NONE', 'RSS', '[]', 'PHOTO', '{"times_per_week":{"default":1}}', '발행 글 RSS 확인'),
('코딩 중 폰 유튜브 금지', NULL, 'CODING', 'PHONE','USAGE','NONE', NULL, '["PACKAGE_USAGE_STATS"]', 'GROUP_CHECK', NULL, '시간대 앱 사용 측정');