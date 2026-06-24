-- 경로: src/main/resources/db/migration/V3__create_routine_tables.sql
-- 루틴 템플릿 카탈로그(정적). RoutineTemplate은 BIGINT auto_increment 유지(원본 스키마 그대로).
-- 구조:
--   · RoutineTemplate      : 루틴 코어(이름/설명/카테고리/목표 파라미터 스키마/근거).
--   · RoutineVerification  : 인증 정의(자동/수동 신호·권한·method). RoutineTemplate와 1:1(templateId = PK이자 FK).
--   · 시드는 코어/인증을 같은 templateId(1..105)로 나눠 넣는다. verificationMethod는 신호+키워드 규칙으로 1회 백필.
--   · has_auto/default_method는 컬럼 없이 비즈니스 로직에서 계산(autoVerificationType 기준).
--   · FULLTEXT(ngram)는 챌린지 매칭이 의존하는 기능성 인덱스라 유지.

CREATE TABLE RoutineTemplate (
                                 id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                                 name         VARCHAR(100)    NOT NULL,
                                 description  VARCHAR(255)    NULL,
                                 category     ENUM('EXERCISE','READING','MEDITATION','HEALTH','WAKEUP',
                                  'WORK','STUDY','HOBBY','COOKING','FINANCE',
                                  'ENVIRONMENT','RELATIONSHIP','MUSIC','WRITING','CODING') NOT NULL,

    -- 목표 파라미터 스키마. 예: {"distance_km":{"default":3,"unit":"km","min":1,"max":50}}
                                 paramSchema  JSON            NULL,
                                 rationale    VARCHAR(255)    NULL,
                                 createdAt    DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                                 updatedAt    DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

                                 PRIMARY KEY (id),
                                 FULLTEXT KEY ftxNameDesc (name, description) WITH PARSER ngram
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ===== 인증 정의(RoutineTemplate와 1:1, templateId가 PK이자 FK) =====
CREATE TABLE RoutineVerification (
                                 templateId               BIGINT UNSIGNED NOT NULL,

    -- 자동 인증 옵션(자동 불가 루틴은 전부 NULL)
                                 autoVerificationType     ENUM('PHONE','HEALTH_CONNECT','EXTERNAL') NULL,
                                 autoSignalSource         ENUM('GEOFENCE','GPS','ACTIVITY','SLEEP','USAGE',
                                  'APP_FEATURE','HC_RECORD','EXTERNAL_API') NULL,
                                 autoWearableReq          ENUM('NONE','OPTIONAL','REQUIRED') NULL,
                                 autoExternalService      VARCHAR(40)     NULL,
                                 autoRequiredPermissions  JSON            NULL,

    -- 수동 인증 옵션(항상 존재)
                                 manualSignalSource       ENUM('PHOTO','GROUP_CHECK') NOT NULL DEFAULT 'PHOTO',

    -- 인증 method(신호+name/rationale 키워드 규칙으로 백필): WAKE/SCREEN_TIME_MIN/SCREEN_TIME_MAX/
    --   GPS_PRESENCE/GPS_DISTANCE/SLEEP/PHOTO/SELF_CHECK
                                 verificationMethod       VARCHAR(20)     NULL,

                                 PRIMARY KEY (templateId),
                                 CONSTRAINT fkRoutineVerificationTemplate
                                     FOREIGN KEY (templateId) REFERENCES RoutineTemplate(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ===== 시드: RoutineTemplate 105개 (코어) =====
INSERT INTO RoutineTemplate (id, name, description, category, paramSchema, rationale) VALUES
(1, '헬스장 가서 1시간 운동', NULL, 'EXERCISE', '{"duration_min":{"default":60,"unit":"min"}}', '장소 체류 시간 감지'),
(2, '아침 3km 달리기', NULL, 'EXERCISE', '{"distance_km":{"default":3,"unit":"km"},"duration_min":{"default":null,"unit":"min"}}', '연속 측위 누적거리'),
(3, '하루 8,000보 걷기', NULL, 'EXERCISE', '{"steps":{"default":8000,"unit":"steps"}}', 'HC 걸음수 집계 — 폰 보행센서 미사용'),
(4, '홈트 30분', '영상 따라하기', 'EXERCISE', '{"duration_min":{"default":30,"unit":"min"}}', '실내 맨몸 — 폰 신호 없음'),
(5, '주 3회 수영장 가기', NULL, 'EXERCISE', '{"times_per_week":{"default":3}}', '장소 방문 감지'),
(6, '자전거로 출퇴근', NULL, 'EXERCISE', NULL, 'ON_BICYCLE 전환 감지'),
(7, '운동 후 스트레칭 10분', NULL, 'EXERCISE', '{"duration_min":{"default":10,"unit":"min"}}', '행위 신호 없음'),
(8, 'e북 앱 30분 읽기', NULL, 'READING', '{"duration_min":{"default":30,"unit":"min"}}', '대상 앱 사용시간'),
(9, '종이책 30분 읽기', NULL, 'READING', '{"duration_min":{"default":30,"unit":"min"}}', '오프라인 — 신호 없음'),
(10, '주말 도서관 가기', NULL, 'READING', NULL, '장소 방문 감지'),
(11, '자기 전 10페이지 읽기', NULL, 'READING', '{"pages":{"default":10}}', '오프라인 — 신호 없음'),
(12, '출근길 오디오북 20분', NULL, 'READING', '{"duration_min":{"default":20,"unit":"min"}}', '대상 앱 사용시간'),
(13, '한 줄 독서 기록 남기기', NULL, 'READING', NULL, '인앱 작성이 증거'),
(14, '한 달에 책 2권 완독', NULL, 'READING', '{"books_per_month":{"default":2}}', '완독 측정 불가'),
(15, '아침 명상 앱 10분', NULL, 'MEDITATION', '{"duration_min":{"default":10,"unit":"min"}}', '대상 앱 사용시간'),
(16, '자기 전 호흡 명상 5분', NULL, 'MEDITATION', '{"duration_min":{"default":5,"unit":"min"}}', '앱 사용시간 + 시간대'),
(17, '요가원·명상센터 가기', NULL, 'MEDITATION', NULL, '장소 방문 감지'),
(18, '워치 마음챙김 세션 기록', NULL, 'MEDITATION', '{"duration_min":{"default":10,"unit":"min"}}', 'MindfulnessSessionRecord — 워치 필수'),
(19, '폰 없는 15분 (화면 OFF)', NULL, 'MEDITATION', '{"duration_min":{"default":15,"unit":"min"}}', '화면 OFF 유지 감지'),
(20, '명상 일지 쓰기', NULL, 'MEDITATION', NULL, '인앱 작성이 증거'),
(21, '명상 공간 인증샷', NULL, 'MEDITATION', NULL, '행위 신호 없음'),
(22, '하루 물 2L 마시기', NULL, 'HEALTH', '{"volume_l":{"default":2,"unit":"L"}}', 'HC 음수는 손입력뿐 — 자동 신호 없음'),
(23, '영양제 챙겨 먹기', NULL, 'HEALTH', NULL, '행위 신호 없음'),
(24, '12시 전에 잠들기', NULL, 'HEALTH', '{"target_time":{"default":"00:00","unit":"hh:mm"}}', '야간 마지막 화면 OFF 시각'),
(25, '7시간 이상 수면', NULL, 'HEALTH', '{"sleep_hours":{"default":7,"unit":"h"}}', 'Sleep API 수면 구간 (폰 단독)'),
(26, '하루 10,000보', NULL, 'HEALTH', '{"steps":{"default":10000,"unit":"steps"}}', 'HC 걸음수 집계'),
(27, '주 1회 체중 기록', NULL, 'HEALTH', '{"times_per_week":{"default":1}}', 'WeightRecord — 스마트체중계 한정 자동'),
(28, '점심 후 10분 산책', NULL, 'HEALTH', '{"duration_min":{"default":10,"unit":"min"}}', 'WALKING 전환 세션 (보조신호)'),
(29, '아침 7시 전에 일어나기', NULL, 'WAKEUP', '{"target_time":{"default":"07:00","unit":"hh:mm"}}', '당일 첫 KEYGUARD_HIDDEN'),
(30, '기상 후 1시간 폰 금지', NULL, 'WAKEUP', '{"duration_min":{"default":60,"unit":"min"}}', '시간대 앱 사용 0'),
(31, '미라클모닝 책상 인증샷', NULL, 'WAKEUP', NULL, '행위는 사진'),
(32, '기상 직후 물 한 잔', NULL, 'WAKEUP', NULL, '행위 신호 없음'),
(33, '알람 한 번에 끄고 미션 수행', NULL, 'WAKEUP', NULL, '인앱 알람 해제가 증거'),
(34, '주말에도 8시 전 기상', NULL, 'WAKEUP', '{"target_time":{"default":"08:00","unit":"hh:mm"}}', '첫 잠금해제 시각 (요일 조건)'),
(35, '기상 후 이불 정리', NULL, 'WAKEUP', NULL, '행위 신호 없음'),
(36, '9시 전 사무실 도착', NULL, 'WORK', '{"target_time":{"default":"09:00","unit":"hh:mm"}}', '장소 도착 시각'),
(37, '오전 딥워크 2시간 (SNS 금지)', NULL, 'WORK', '{"duration_min":{"default":120,"unit":"min"}}', '시간대 SNS 사용 측정'),
(38, '업무 시작 전 투두리스트 작성', NULL, 'WORK', NULL, '인앱 작성이 증거'),
(39, '점심 후 카페에서 30분 집중', NULL, 'WORK', '{"duration_min":{"default":30,"unit":"min"}}', '장소 체류 — 등록 장소 한정'),
(40, '퇴근 후 업무 메신저 안 보기', NULL, 'WORK', NULL, '시간대 대상 앱 사용'),
(41, '금요일 주간 회고 작성', NULL, 'WORK', NULL, '인앱 작성이 증거'),
(42, '퇴근 전 책상 정리', NULL, 'WORK', NULL, '행위 신호 없음'),
(43, '독서실 3시간 공부', NULL, 'STUDY', '{"duration_min":{"default":180,"unit":"min"}}', '장소 체류 시간'),
(44, '인강 1시간 듣기', NULL, 'STUDY', '{"duration_min":{"default":60,"unit":"min"}}', '대상 앱 사용시간'),
(45, '공부 시간대 폰 금지 (19~22시)', NULL, 'STUDY', '{"start_time":{"default":"19:00"},"end_time":{"default":"22:00"}}', '시간대 앱 사용'),
(46, '암기 앱 20분', NULL, 'STUDY', '{"duration_min":{"default":20,"unit":"min"}}', '대상 앱 사용시간'),
(47, '오답노트 정리', NULL, 'STUDY', NULL, '오프라인 — 신호 없음'),
(48, '도서관 21시까지 공부', NULL, 'STUDY', '{"target_time":{"default":"21:00","unit":"hh:mm"}}', '장소 이탈 시각'),
(49, '스터디 모임 참석', NULL, 'STUDY', NULL, '장소 방문'),
(50, '드로잉 앱 30분 그리기', NULL, 'HOBBY', '{"duration_min":{"default":30,"unit":"min"}}', '대상 앱 사용시간'),
(51, '그림 한 장 완성 인증', NULL, 'HOBBY', NULL, '결과물만 존재'),
(52, '클라이밍장 가기', NULL, 'HOBBY', NULL, '장소 방문'),
(53, '사진 산책 (출사) 1시간', NULL, 'HOBBY', '{"duration_min":{"default":60,"unit":"min"}}', '결과물 사진이 곧 인증'),
(54, '뜨개질·공예 30분', NULL, 'HOBBY', '{"duration_min":{"default":30,"unit":"min"}}', '행위 신호 없음'),
(55, '공방 수업 참석', NULL, 'HOBBY', NULL, '장소 방문'),
(56, '퍼즐·레고 30분 조립', NULL, 'HOBBY', '{"duration_min":{"default":30,"unit":"min"}}', '행위 신호 없음'),
(57, '아침 직접 차려 먹기', NULL, 'COOKING', NULL, '요리·식사 신호 없음'),
(58, '주 3회 도시락 싸기', NULL, 'COOKING', '{"times_per_week":{"default":3}}', '요리 신호 없음'),
(59, '배달 대신 집밥', NULL, 'COOKING', NULL, '배달앱 0분(앱사용 보조) + 집밥 사진'),
(60, '주말 새 레시피 도전', NULL, 'COOKING', NULL, '요리 신호 없음'),
(61, '세 끼 식단 기록', NULL, 'COOKING', NULL, '식사 신호 없음'),
(62, '주 1회 장보기', NULL, 'COOKING', '{"times_per_week":{"default":1}}', '마트 방문 감지'),
(63, '식사 후 바로 설거지', NULL, 'COOKING', NULL, '행위 신호 없음'),
(64, '가계부 앱 5분 작성', NULL, 'FINANCE', '{"duration_min":{"default":5,"unit":"min"}}', '앱 사용시간 (켜놓기 치팅 여지)'),
(65, '주 2회 무지출 데이', NULL, 'FINANCE', '{"times_per_week":{"default":2}}', '부재(안 샀음)는 증명 불가'),
(66, '경제 뉴스 15분 읽기', NULL, 'FINANCE', '{"duration_min":{"default":15,"unit":"min"}}', '대상 앱 사용시간'),
(67, '저녁 소비 회고 쓰기', NULL, 'FINANCE', NULL, '인앱 작성이 증거'),
(68, '재테크 책 30분', NULL, 'FINANCE', '{"duration_min":{"default":30,"unit":"min"}}', '오프라인 독서'),
(69, '쇼핑앱 하루 30분 이하', NULL, 'FINANCE', '{"max_minutes":{"default":30,"unit":"min"}}', '대상 앱 사용 상한'),
(70, '주식앱 하루 10분 이하', NULL, 'FINANCE', '{"max_minutes":{"default":10,"unit":"min"}}', '대상 앱 사용 상한'),
(71, '텀블러 사용하기', NULL, 'ENVIRONMENT', NULL, '행위 신호 없음'),
(72, '플로깅 30분', NULL, 'ENVIRONMENT', '{"duration_min":{"default":30,"unit":"min"}}', '줍는 행위 신호 없음'),
(73, '장바구니 들고 장보기', NULL, 'ENVIRONMENT', NULL, '행위 신호 없음'),
(74, '분리수거 하기', NULL, 'ENVIRONMENT', NULL, '행위 신호 없음'),
(75, '도보 출근 (차 대신)', NULL, 'ENVIRONMENT', NULL, 'WALKING 전환 + 도착 감지'),
(76, '다회용기 포장 주문', NULL, 'ENVIRONMENT', NULL, '행위 신호 없음'),
(77, '잔반 없는 식사 (빈 그릇)', NULL, 'ENVIRONMENT', NULL, '식사 결과 신호 없음'),
(78, '부모님께 주 2회 안부 전화', NULL, 'RELATIONSHIP', '{"times_per_week":{"default":2}}', '통화기록 접근 정책상 차단'),
(79, '가족 저녁 함께 먹기', NULL, 'RELATIONSHIP', NULL, '함께함 측정 불가'),
(80, '친구 약속 참석', NULL, 'RELATIONSHIP', NULL, '장소 방문 — 챌린지별 위치 핀 전제'),
(81, '월 1회 본가 방문', NULL, 'RELATIONSHIP', '{"times_per_month":{"default":1}}', '장소 방문'),
(82, '하루 한 번 칭찬·감사 표현', NULL, 'RELATIONSHIP', '{"times_per_day":{"default":1}}', '사회적 행위 — 신호 없음'),
(83, '동호회 정기모임 출석', NULL, 'RELATIONSHIP', NULL, '장소 방문'),
(84, '주 1회 손편지·장문 편지', NULL, 'RELATIONSHIP', '{"times_per_week":{"default":1}}', '오프라인 — 신호 없음'),
(85, '피아노 연습 30분', NULL, 'MUSIC', '{"duration_min":{"default":30,"unit":"min"}}', '연주(소리) 폰 신호 없음'),
(86, '악기 학습 앱 20분', NULL, 'MUSIC', '{"duration_min":{"default":20,"unit":"min"}}', '대상 앱 사용시간'),
(87, '연습실 가기', NULL, 'MUSIC', NULL, '장소 방문'),
(88, '보컬 연습 인증', NULL, 'MUSIC', NULL, '오디오 분석 범위 밖'),
(89, '새 곡 한 곡 완주 연습', NULL, 'MUSIC', NULL, '연주 신호 없음'),
(90, '합주·밴드 연습 참석', NULL, 'MUSIC', NULL, '장소 방문'),
(91, '음악 이론 앱 15분', NULL, 'MUSIC', '{"duration_min":{"default":15,"unit":"min"}}', '대상 앱 사용시간'),
(92, '하루 일기 쓰기', NULL, 'WRITING', NULL, '인앱 작성 + 글자 수 검증'),
(93, '모닝 페이지 (아침 글쓰기)', NULL, 'WRITING', NULL, '인앱 작성 + 시간대 검증'),
(94, '블로그 주 1회 발행', NULL, 'WRITING', '{"times_per_week":{"default":1}}', '발행 글 RSS 확인'),
(95, '감사일기 3줄', NULL, 'WRITING', '{"lines":{"default":3}}', '인앱 작성이 증거'),
(96, '하루 500자 이상 글쓰기', NULL, 'WRITING', '{"min_chars":{"default":500}}', '인앱 글자 수 검증'),
(97, '필사 한 페이지', NULL, 'WRITING', '{"pages":{"default":1}}', '손글씨 — 오프라인'),
(98, '독서 노트 정리', NULL, 'WRITING', NULL, '오프라인 — 신호 없음'),
(99, '1일 1커밋', NULL, 'CODING', '{"commits_per_day":{"default":1}}', 'GitHub 기여 내역 조회'),
(100, '코드포스 1일 1문제', '구 백준 1일 1솔 대체 (BOJ 2026-04 종료·부활 불확실)', 'CODING', '{"problems_per_day":{"default":1}}', 'Codeforces user.status 공개 API. 프로그래머스 택 시 수동(API 없음)'),
(101, '알고리즘 스터디 참석', NULL, 'CODING', NULL, '장소 방문'),
(102, 'CS 인강 30분', NULL, 'CODING', '{"duration_min":{"default":30,"unit":"min"}}', '대상 앱 사용시간'),
(103, '사이드 프로젝트 1시간', NULL, 'CODING', '{"duration_min":{"default":60,"unit":"min"}}', 'WakaTime/GitHub 코딩시간 (PC라 폰 신호 없음)'),
(104, '기술 블로그 주 1회', NULL, 'CODING', '{"times_per_week":{"default":1}}', '발행 글 RSS 확인'),
(105, '코딩 중 폰 유튜브 금지', NULL, 'CODING', NULL, '시간대 앱 사용 측정');

-- ===== 시드: RoutineVerification 105개 (인증 — RoutineTemplate와 1:1) =====
INSERT INTO RoutineVerification
(templateId, autoVerificationType, autoSignalSource, autoWearableReq, autoExternalService, autoRequiredPermissions, manualSignalSource) VALUES
(1, 'PHONE', 'GEOFENCE', 'NONE', NULL, '["ACCESS_FINE_LOCATION","ACCESS_BACKGROUND_LOCATION"]', 'PHOTO'),
(2, 'PHONE', 'GPS', 'OPTIONAL', NULL, '["ACCESS_FINE_LOCATION","ACCESS_BACKGROUND_LOCATION"]', 'PHOTO'),
(3, 'HEALTH_CONNECT', 'HC_RECORD', 'OPTIONAL', NULL, '["android.permission.health.READ_STEPS"]', 'PHOTO'),
(4, NULL, NULL, NULL, NULL, NULL, 'PHOTO'),
(5, 'PHONE', 'GEOFENCE', 'NONE', NULL, '["ACCESS_FINE_LOCATION","ACCESS_BACKGROUND_LOCATION"]', 'PHOTO'),
(6, 'PHONE', 'ACTIVITY', 'NONE', NULL, '["ACTIVITY_RECOGNITION"]', 'PHOTO'),
(7, NULL, NULL, NULL, NULL, NULL, 'PHOTO'),
(8, 'PHONE', 'USAGE', 'NONE', NULL, '["PACKAGE_USAGE_STATS"]', 'PHOTO'),
(9, NULL, NULL, NULL, NULL, NULL, 'PHOTO'),
(10, 'PHONE', 'GEOFENCE', 'NONE', NULL, '["ACCESS_FINE_LOCATION","ACCESS_BACKGROUND_LOCATION"]', 'PHOTO'),
(11, NULL, NULL, NULL, NULL, NULL, 'PHOTO'),
(12, 'PHONE', 'USAGE', 'NONE', NULL, '["PACKAGE_USAGE_STATS"]', 'PHOTO'),
(13, 'PHONE', 'APP_FEATURE', 'NONE', NULL, '[]', 'GROUP_CHECK'),
(14, NULL, NULL, NULL, NULL, NULL, 'PHOTO'),
(15, 'PHONE', 'USAGE', 'NONE', NULL, '["PACKAGE_USAGE_STATS"]', 'PHOTO'),
(16, 'PHONE', 'USAGE', 'NONE', NULL, '["PACKAGE_USAGE_STATS"]', 'PHOTO'),
(17, 'PHONE', 'GEOFENCE', 'NONE', NULL, '["ACCESS_FINE_LOCATION","ACCESS_BACKGROUND_LOCATION"]', 'PHOTO'),
(18, 'HEALTH_CONNECT', 'HC_RECORD', 'REQUIRED', NULL, '["android.permission.health.READ_MINDFULNESS"]', 'PHOTO'),
(19, 'PHONE', 'USAGE', 'NONE', NULL, '["PACKAGE_USAGE_STATS"]', 'GROUP_CHECK'),
(20, 'PHONE', 'APP_FEATURE', 'NONE', NULL, '[]', 'GROUP_CHECK'),
(21, NULL, NULL, NULL, NULL, NULL, 'PHOTO'),
(22, NULL, NULL, NULL, NULL, NULL, 'GROUP_CHECK'),
(23, NULL, NULL, NULL, NULL, NULL, 'PHOTO'),
(24, 'PHONE', 'USAGE', 'NONE', NULL, '["PACKAGE_USAGE_STATS"]', 'GROUP_CHECK'),
(25, 'PHONE', 'SLEEP', 'OPTIONAL', NULL, '["ACTIVITY_RECOGNITION"]', 'PHOTO'),
(26, 'HEALTH_CONNECT', 'HC_RECORD', 'OPTIONAL', NULL, '["android.permission.health.READ_STEPS"]', 'PHOTO'),
(27, 'HEALTH_CONNECT', 'HC_RECORD', 'NONE', NULL, '["android.permission.health.READ_WEIGHT"]', 'PHOTO'),
(28, 'PHONE', 'ACTIVITY', 'OPTIONAL', NULL, '["ACTIVITY_RECOGNITION"]', 'PHOTO'),
(29, 'PHONE', 'USAGE', 'NONE', NULL, '["PACKAGE_USAGE_STATS"]', 'GROUP_CHECK'),
(30, 'PHONE', 'USAGE', 'NONE', NULL, '["PACKAGE_USAGE_STATS"]', 'GROUP_CHECK'),
(31, NULL, NULL, NULL, NULL, NULL, 'PHOTO'),
(32, NULL, NULL, NULL, NULL, NULL, 'PHOTO'),
(33, 'PHONE', 'APP_FEATURE', 'NONE', NULL, '[]', 'GROUP_CHECK'),
(34, 'PHONE', 'USAGE', 'NONE', NULL, '["PACKAGE_USAGE_STATS"]', 'GROUP_CHECK'),
(35, NULL, NULL, NULL, NULL, NULL, 'PHOTO'),
(36, 'PHONE', 'GEOFENCE', 'NONE', NULL, '["ACCESS_FINE_LOCATION","ACCESS_BACKGROUND_LOCATION"]', 'GROUP_CHECK'),
(37, 'PHONE', 'USAGE', 'NONE', NULL, '["PACKAGE_USAGE_STATS"]', 'GROUP_CHECK'),
(38, 'PHONE', 'APP_FEATURE', 'NONE', NULL, '[]', 'GROUP_CHECK'),
(39, 'PHONE', 'GEOFENCE', 'NONE', NULL, '["ACCESS_FINE_LOCATION","ACCESS_BACKGROUND_LOCATION"]', 'PHOTO'),
(40, 'PHONE', 'USAGE', 'NONE', NULL, '["PACKAGE_USAGE_STATS"]', 'GROUP_CHECK'),
(41, 'PHONE', 'APP_FEATURE', 'NONE', NULL, '[]', 'GROUP_CHECK'),
(42, NULL, NULL, NULL, NULL, NULL, 'PHOTO'),
(43, 'PHONE', 'GEOFENCE', 'NONE', NULL, '["ACCESS_FINE_LOCATION","ACCESS_BACKGROUND_LOCATION"]', 'PHOTO'),
(44, 'PHONE', 'USAGE', 'NONE', NULL, '["PACKAGE_USAGE_STATS"]', 'PHOTO'),
(45, 'PHONE', 'USAGE', 'NONE', NULL, '["PACKAGE_USAGE_STATS"]', 'GROUP_CHECK'),
(46, 'PHONE', 'USAGE', 'NONE', NULL, '["PACKAGE_USAGE_STATS"]', 'PHOTO'),
(47, NULL, NULL, NULL, NULL, NULL, 'PHOTO'),
(48, 'PHONE', 'GEOFENCE', 'NONE', NULL, '["ACCESS_FINE_LOCATION","ACCESS_BACKGROUND_LOCATION"]', 'GROUP_CHECK'),
(49, 'PHONE', 'GEOFENCE', 'NONE', NULL, '["ACCESS_FINE_LOCATION","ACCESS_BACKGROUND_LOCATION"]', 'PHOTO'),
(50, 'PHONE', 'USAGE', 'NONE', NULL, '["PACKAGE_USAGE_STATS"]', 'PHOTO'),
(51, NULL, NULL, NULL, NULL, NULL, 'PHOTO'),
(52, 'PHONE', 'GEOFENCE', 'NONE', NULL, '["ACCESS_FINE_LOCATION","ACCESS_BACKGROUND_LOCATION"]', 'PHOTO'),
(53, NULL, NULL, NULL, NULL, NULL, 'PHOTO'),
(54, NULL, NULL, NULL, NULL, NULL, 'PHOTO'),
(55, 'PHONE', 'GEOFENCE', 'NONE', NULL, '["ACCESS_FINE_LOCATION","ACCESS_BACKGROUND_LOCATION"]', 'PHOTO'),
(56, NULL, NULL, NULL, NULL, NULL, 'PHOTO'),
(57, NULL, NULL, NULL, NULL, NULL, 'PHOTO'),
(58, NULL, NULL, NULL, NULL, NULL, 'PHOTO'),
(59, NULL, NULL, NULL, NULL, NULL, 'PHOTO'),
(60, NULL, NULL, NULL, NULL, NULL, 'PHOTO'),
(61, NULL, NULL, NULL, NULL, NULL, 'PHOTO'),
(62, 'PHONE', 'GEOFENCE', 'NONE', NULL, '["ACCESS_FINE_LOCATION","ACCESS_BACKGROUND_LOCATION"]', 'PHOTO'),
(63, NULL, NULL, NULL, NULL, NULL, 'PHOTO'),
(64, 'PHONE', 'USAGE', 'NONE', NULL, '["PACKAGE_USAGE_STATS"]', 'PHOTO'),
(65, NULL, NULL, NULL, NULL, NULL, 'GROUP_CHECK'),
(66, 'PHONE', 'USAGE', 'NONE', NULL, '["PACKAGE_USAGE_STATS"]', 'PHOTO'),
(67, 'PHONE', 'APP_FEATURE', 'NONE', NULL, '[]', 'GROUP_CHECK'),
(68, NULL, NULL, NULL, NULL, NULL, 'PHOTO'),
(69, 'PHONE', 'USAGE', 'NONE', NULL, '["PACKAGE_USAGE_STATS"]', 'GROUP_CHECK'),
(70, 'PHONE', 'USAGE', 'NONE', NULL, '["PACKAGE_USAGE_STATS"]', 'GROUP_CHECK'),
(71, NULL, NULL, NULL, NULL, NULL, 'PHOTO'),
(72, NULL, NULL, NULL, NULL, NULL, 'PHOTO'),
(73, NULL, NULL, NULL, NULL, NULL, 'PHOTO'),
(74, NULL, NULL, NULL, NULL, NULL, 'PHOTO'),
(75, 'PHONE', 'ACTIVITY', 'OPTIONAL', NULL, '["ACTIVITY_RECOGNITION","ACCESS_FINE_LOCATION"]', 'PHOTO'),
(76, NULL, NULL, NULL, NULL, NULL, 'PHOTO'),
(77, NULL, NULL, NULL, NULL, NULL, 'PHOTO'),
(78, NULL, NULL, NULL, NULL, NULL, 'GROUP_CHECK'),
(79, NULL, NULL, NULL, NULL, NULL, 'PHOTO'),
(80, 'PHONE', 'GEOFENCE', 'NONE', NULL, '["ACCESS_FINE_LOCATION","ACCESS_BACKGROUND_LOCATION"]', 'PHOTO'),
(81, 'PHONE', 'GEOFENCE', 'NONE', NULL, '["ACCESS_FINE_LOCATION","ACCESS_BACKGROUND_LOCATION"]', 'PHOTO'),
(82, NULL, NULL, NULL, NULL, NULL, 'GROUP_CHECK'),
(83, 'PHONE', 'GEOFENCE', 'NONE', NULL, '["ACCESS_FINE_LOCATION","ACCESS_BACKGROUND_LOCATION"]', 'PHOTO'),
(84, NULL, NULL, NULL, NULL, NULL, 'PHOTO'),
(85, NULL, NULL, NULL, NULL, NULL, 'PHOTO'),
(86, 'PHONE', 'USAGE', 'NONE', NULL, '["PACKAGE_USAGE_STATS"]', 'PHOTO'),
(87, 'PHONE', 'GEOFENCE', 'NONE', NULL, '["ACCESS_FINE_LOCATION","ACCESS_BACKGROUND_LOCATION"]', 'PHOTO'),
(88, NULL, NULL, NULL, NULL, NULL, 'PHOTO'),
(89, NULL, NULL, NULL, NULL, NULL, 'PHOTO'),
(90, 'PHONE', 'GEOFENCE', 'NONE', NULL, '["ACCESS_FINE_LOCATION","ACCESS_BACKGROUND_LOCATION"]', 'PHOTO'),
(91, 'PHONE', 'USAGE', 'NONE', NULL, '["PACKAGE_USAGE_STATS"]', 'PHOTO'),
(92, 'PHONE', 'APP_FEATURE', 'NONE', NULL, '[]', 'GROUP_CHECK'),
(93, 'PHONE', 'APP_FEATURE', 'NONE', NULL, '[]', 'GROUP_CHECK'),
(94, 'EXTERNAL', 'EXTERNAL_API', 'NONE', 'RSS', '[]', 'PHOTO'),
(95, 'PHONE', 'APP_FEATURE', 'NONE', NULL, '[]', 'GROUP_CHECK'),
(96, 'PHONE', 'APP_FEATURE', 'NONE', NULL, '[]', 'GROUP_CHECK'),
(97, NULL, NULL, NULL, NULL, NULL, 'PHOTO'),
(98, NULL, NULL, NULL, NULL, NULL, 'PHOTO'),
(99, 'EXTERNAL', 'EXTERNAL_API', 'NONE', 'GitHub', '[]', 'PHOTO'),
(100, 'EXTERNAL', 'EXTERNAL_API', 'NONE', 'Codeforces', '[]', 'PHOTO'),
(101, 'PHONE', 'GEOFENCE', 'NONE', NULL, '["ACCESS_FINE_LOCATION","ACCESS_BACKGROUND_LOCATION"]', 'PHOTO'),
(102, 'PHONE', 'USAGE', 'NONE', NULL, '["PACKAGE_USAGE_STATS"]', 'PHOTO'),
(103, 'EXTERNAL', 'EXTERNAL_API', 'NONE', 'WakaTime', '[]', 'PHOTO'),
(104, 'EXTERNAL', 'EXTERNAL_API', 'NONE', 'RSS', '[]', 'PHOTO'),
(105, 'PHONE', 'USAGE', 'NONE', NULL, '["PACKAGE_USAGE_STATS"]', 'GROUP_CHECK');

-- ===== verificationMethod 백필(구 V7 로직) — 신호원 + name/rationale 키워드 규칙 =====
UPDATE RoutineVerification rv
    JOIN RoutineTemplate rt ON rt.id = rv.templateId
SET rv.verificationMethod = CASE
    -- 수동 전용(자동 신호 없음)
    WHEN rv.autoSignalSource IS NULL THEN
        CASE WHEN rv.manualSignalSource = 'GROUP_CHECK' THEN 'SELF_CHECK' ELSE 'PHOTO' END
    -- MVP 미지원 자동(헬스커넥트·외부연동·앱기능) → 수동 폴백(Phase 2)
    WHEN rv.autoSignalSource IN ('HC_RECORD','EXTERNAL_API','APP_FEATURE') THEN 'PHOTO'
    -- GPS 계열
    WHEN rv.autoSignalSource = 'GEOFENCE' THEN 'GPS_PRESENCE'
    WHEN rv.autoSignalSource = 'GPS'      THEN 'GPS_DISTANCE'
    WHEN rv.autoSignalSource = 'ACTIVITY' THEN 'GPS_DISTANCE'
    WHEN rv.autoSignalSource = 'SLEEP'    THEN 'SLEEP'
    -- USAGE: WAKE(첫 잠금해제) / SCREEN_TIME_MAX(제약·금지·상한) / SCREEN_TIME_MIN(사용시간 도달)
    WHEN rv.autoSignalSource = 'USAGE' AND (
            rt.rationale LIKE '%KEYGUARD%' OR rt.rationale LIKE '%첫 잠금해제%' OR rt.rationale LIKE '%첫 기상%'
         ) THEN 'WAKE'
    WHEN rv.autoSignalSource = 'USAGE' AND (
            rt.name LIKE '%금지%' OR rt.name LIKE '%이하%' OR rt.name LIKE '%안 보기%' OR rt.name LIKE '%안보기%'
            OR rt.name LIKE '%OFF%' OR rt.name LIKE '%잠들기%'
            OR rt.rationale LIKE '%상한%' OR rt.rationale LIKE '%금지%' OR rt.rationale LIKE '%OFF%' OR rt.rationale LIKE '%사용 0%'
         ) THEN 'SCREEN_TIME_MAX'
    WHEN rv.autoSignalSource = 'USAGE' THEN 'SCREEN_TIME_MIN'
    ELSE 'PHOTO'
END;
