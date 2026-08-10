-- =====================================================================
-- V9: 루틴 카탈로그 시드 — 「루틴 테이블」 문서의 판정 모델별 루틴 중
--     **현재 자동 인증이 가능한 것만** 투입한다.
--
--  투입 대상(문서 §1~§7 — 지금 evaluator 가 이미 처리하는 판정 모델):
--    §1 위치 방문   GPS_PRESENCE  15건
--    §2 위치 회피   GPS_AVOID     12건
--    §3 걸음·거리   HEALTH        10건
--    §4 앱 최대형   SCREEN_TIME_MAX 13건
--    §5 앱 최소형   SCREEN_TIME_MIN 14건
--    §6 기상        WAKE           7건
--    §7 수면        SLEEP          8건
--                                 ─────
--                                  79건
--
--  제외 대상과 사유:
--    §8~§11  시간대 제한·화면 이벤트·위치+시간대·종속 인증 → "판정기/신호 확장 필요"
--    §12     Health Connect 운동 세션·활동량            → "추가 데이터 타입 필요"
--    §13     복합 조건(AND)                              → 1차 출시 후순위
--    §14     자동 인증에 넣지 않는 편이 좋은 루틴        → 신뢰 가능한 신호 없음
--    §1 의 「정시 출근하기」·「일찍 귀가하기」            → 문서 주석대로 실제로는 §10(위치+시간대)
--    §6 의 「알람 한 번에 일어나기」                      → §14 "첫 잠금 해제 시각만으로는 판정 불충분"
--
--  id 는 1001~ 로 띄워 둔다. 스테이징에 남아 있을 수 있는 구 템플릿(AUTO_INCREMENT=106
--  이하)과 충돌하지 않게 하기 위해서다.
--
--  paramSchema 의 키는 VerificationConfigFactory 가 읽는 키와 1:1 이어야 한다
--  (WAKE=target_time / SCREEN_TIME_*=duration_min / HEALTH=steps·distance_km /
--   GPS_*=duration_min / SLEEP=bedtime_before·sleep_hours).
--   VISIT/AVOID 와 대상 앱·장소는 목표값이 아니라 방 설정이라 params 로 받지 않는다 —
--   방향은 verificationMethod 태그가, 장소·앱은 멤버별 셋업이 정한다.
-- =====================================================================

-- ── §1 위치 — 특정 장소 방문 (GPS_PRESENCE) ────────────────────────────
INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES
(1001, '헬스장 가기', '등록한 헬스장에 머문 시간으로 운동 여부를 확인해요.', 'EXERCISE', '{"duration_min":{"default":60,"unit":"min","min":10,"max":480}}', '위치 체류 시간으로 방문을 판정'),
(1002, '스터디 카페 가기', '등록한 스터디 카페에 머문 시간으로 공부 시간을 확인해요.', 'STUDY', '{"duration_min":{"default":120,"unit":"min","min":10,"max":720}}', '위치 체류 시간으로 방문을 판정'),
(1003, '도서관 가기', '등록한 도서관에 머문 시간으로 방문을 확인해요.', 'STUDY', '{"duration_min":{"default":120,"unit":"min","min":10,"max":720}}', '위치 체류 시간으로 방문을 판정'),
(1004, '수영장 가기', '등록한 수영장에 머문 시간으로 운동 여부를 확인해요.', 'EXERCISE', '{"duration_min":{"default":60,"unit":"min","min":10,"max":480}}', '위치 체류 시간으로 방문을 판정'),
(1005, '클라이밍장 가기', '등록한 클라이밍장에 머문 시간으로 운동 여부를 확인해요.', 'EXERCISE', '{"duration_min":{"default":90,"unit":"min","min":10,"max":480}}', '위치 체류 시간으로 방문을 판정'),
(1006, '필라테스·요가 수업 참석', '등록한 센터에 머문 시간으로 수업 참석을 확인해요.', 'EXERCISE', '{"duration_min":{"default":50,"unit":"min","min":10,"max":300}}', '위치 체류 시간으로 방문을 판정'),
(1007, '학원·과외 빠지지 않기', '등록한 학원에 머문 시간으로 출석을 확인해요.', 'STUDY', '{"duration_min":{"default":120,"unit":"min","min":10,"max":600}}', '위치 체류 시간으로 방문을 판정'),
(1008, '러닝 크루 모임 참석', '등록한 공원·트랙에 머문 시간으로 모임 참석을 확인해요.', 'EXERCISE', '{"duration_min":{"default":60,"unit":"min","min":10,"max":300}}', '위치 체류 시간으로 방문을 판정'),
(1009, '공원 산책하기', '등록한 공원에 머문 시간으로 산책을 확인해요.', 'EXERCISE', '{"duration_min":{"default":30,"unit":"min","min":5,"max":300}}', '위치 체류 시간으로 방문을 판정'),
(1010, '코워킹스페이스 출근하기', '등록한 작업 공간에 머문 시간으로 출근을 확인해요.', 'CAREER_PRODUCTIVITY', '{"duration_min":{"default":180,"unit":"min","min":30,"max":720}}', '위치 체류 시간으로 방문을 판정'),
(1011, '학교 강의 출석하기', '등록한 강의동에 머문 시간으로 출석을 확인해요.', 'STUDY', '{"duration_min":{"default":60,"unit":"min","min":10,"max":600}}', '위치 체류 시간으로 방문을 판정'),
(1012, '병원·재활 치료 다녀오기', '등록한 병원에 머문 시간으로 치료 방문을 확인해요.', 'DIET_HEALTH', '{"duration_min":{"default":30,"unit":"min","min":5,"max":480}}', '위치 체류 시간으로 방문을 판정'),
(1013, '동아리·모임 활동 나가기', '등록한 모임 장소에 머문 시간으로 참석을 확인해요.', 'HOBBY', '{"duration_min":{"default":120,"unit":"min","min":10,"max":600}}', '위치 체류 시간으로 방문을 판정'),
(1014, '가족·부모님 집 방문하기', '등록한 가족 집에 머문 시간으로 방문을 확인해요.', 'ETC', '{"duration_min":{"default":60,"unit":"min","min":10,"max":720}}', '위치 체류 시간으로 방문을 판정'),
(1015, '정기 모임 장소 방문하기', '등록한 모임 공간에 머문 시간으로 참석을 확인해요.', 'HOBBY', '{"duration_min":{"default":60,"unit":"min","min":10,"max":600}}', '위치 체류 시간으로 방문을 판정');

INSERT INTO `RoutineVerification`
    (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`)
SELECT `id`, 'PHONE', 'GEOFENCE', 'NONE',
       '["ACCESS_FINE_LOCATION","ACCESS_BACKGROUND_LOCATION"]', 'SELF_CHECK', 'GPS_PRESENCE'
FROM `RoutineTemplate` WHERE `id` BETWEEN 1001 AND 1015;

-- ── §2 위치 — 특정 장소 피하기 (GPS_AVOID) ─────────────────────────────
-- duration_min 은 "잠깐 스친 것"을 실패로 보지 않기 위한 허용 체류 시간이다.
INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES
(1101, '술집 안 가기', '등록한 술집에 머물지 않았는지 확인해요.', 'DETOX', '{"duration_min":{"default":10,"unit":"min","min":1,"max":120}}', '금지 장소 체류로 규칙 위반을 판정'),
(1102, 'PC방 안 가기', '등록한 PC방에 머물지 않았는지 확인해요.', 'DETOX', '{"duration_min":{"default":10,"unit":"min","min":1,"max":120}}', '금지 장소 체류로 규칙 위반을 판정'),
(1103, '야식집 안 가기', '등록한 야식집에 머물지 않았는지 확인해요.', 'DIET_HEALTH', '{"duration_min":{"default":10,"unit":"min","min":1,"max":120}}', '금지 장소 체류로 규칙 위반을 판정'),
(1104, '편의점 들르지 않기', '등록한 편의점에 머물지 않았는지 확인해요.', 'DETOX', '{"duration_min":{"default":5,"unit":"min","min":1,"max":120}}', '금지 장소 체류로 규칙 위반을 판정'),
(1105, '카페 커피 안 사 마시기', '자주 가던 카페에 머물지 않았는지 확인해요.', 'FINANCE', '{"duration_min":{"default":5,"unit":"min","min":1,"max":120}}', '금지 장소 체류로 규칙 위반을 판정'),
(1106, '쇼핑몰 안 가기', '등록한 백화점·아울렛에 머물지 않았는지 확인해요.', 'FINANCE', '{"duration_min":{"default":10,"unit":"min","min":1,"max":120}}', '금지 장소 체류로 규칙 위반을 판정'),
(1107, '노래방·클럽 안 가기', '등록한 노래방·클럽에 머물지 않았는지 확인해요.', 'DETOX', '{"duration_min":{"default":10,"unit":"min","min":1,"max":120}}', '금지 장소 체류로 규칙 위반을 판정'),
(1108, '흡연 구역 피하기', '등록한 흡연 구역에 머물지 않았는지 확인해요.', 'DETOX', '{"duration_min":{"default":5,"unit":"min","min":1,"max":120}}', '금지 장소 체류로 규칙 위반을 판정'),
(1109, '뽑기방·오락실 안 가기', '등록한 인형뽑기방·오락실에 머물지 않았는지 확인해요.', 'FINANCE', '{"duration_min":{"default":10,"unit":"min","min":1,"max":120}}', '금지 장소 체류로 규칙 위반을 판정'),
(1110, '야식 배달 픽업 안 가기', '등록한 배달·포장 매장에 머물지 않았는지 확인해요.', 'DIET_HEALTH', '{"duration_min":{"default":5,"unit":"min","min":1,"max":120}}', '금지 장소 체류로 규칙 위반을 판정'),
(1111, '디저트·베이커리 가게 안 가기', '등록한 디저트 가게에 머물지 않았는지 확인해요.', 'DIET_HEALTH', '{"duration_min":{"default":5,"unit":"min","min":1,"max":120}}', '금지 장소 체류로 규칙 위반을 판정'),
(1112, '카페 들르지 않기', '자주 가던 카페에 머물지 않았는지 확인해요.', 'FINANCE', '{"duration_min":{"default":5,"unit":"min","min":1,"max":120}}', '금지 장소 체류로 규칙 위반을 판정');

INSERT INTO `RoutineVerification`
    (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`)
SELECT `id`, 'PHONE', 'GEOFENCE', 'NONE',
       '["ACCESS_FINE_LOCATION","ACCESS_BACKGROUND_LOCATION"]', 'SELF_CHECK', 'GPS_AVOID'
FROM `RoutineTemplate` WHERE `id` BETWEEN 1101 AND 1112;

-- ── §3 걸음·거리 (HEALTH) ──────────────────────────────────────────────
-- 하루 누적 기준이라 "몇 시에 걷는지"는 판정하지 않는다(문서 참고).
INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES
(1201, '하루 만 보 걷기', '하루 동안 걸은 걸음 수로 확인해요.', 'EXERCISE', '{"steps":{"default":10000,"unit":"count","min":1000,"max":100000}}', '건강 기록의 하루 누적 걸음으로 판정'),
(1202, '하루 6천 보 걷기 (입문)', '하루 동안 걸은 걸음 수로 확인해요.', 'EXERCISE', '{"steps":{"default":6000,"unit":"count","min":1000,"max":100000}}', '건강 기록의 하루 누적 걸음으로 판정'),
(1203, '하루 3km 걷기', '하루 동안 이동한 거리로 확인해요.', 'EXERCISE', '{"distance_km":{"default":3,"unit":"km","min":1,"max":100}}', '건강 기록의 하루 누적 거리로 판정'),
(1204, '5km 러닝하기', '하루 동안 달린 거리로 확인해요.', 'EXERCISE', '{"distance_km":{"default":5,"unit":"km","min":1,"max":100}}', '건강 기록의 하루 누적 거리로 판정'),
(1205, '한 정거장 먼저 내려 걷기', '하루 동안 걸은 걸음 수로 확인해요.', 'EXERCISE', '{"steps":{"default":2000,"unit":"count","min":500,"max":100000}}', '건강 기록의 하루 누적 걸음으로 판정'),
(1206, '점심시간 산책하기', '하루 동안 걸은 걸음 수로 확인해요.', 'EXERCISE', '{"steps":{"default":3000,"unit":"count","min":500,"max":100000}}', '건강 기록의 하루 누적 걸음으로 판정'),
(1207, '주말 장거리 걷기', '하루 동안 이동한 거리로 확인해요.', 'EXERCISE', '{"distance_km":{"default":8,"unit":"km","min":1,"max":100}}', '건강 기록의 하루 누적 거리로 판정'),
(1208, '퇴근 후 동네 한 바퀴', '하루 동안 이동한 거리로 확인해요.', 'EXERCISE', '{"distance_km":{"default":2,"unit":"km","min":1,"max":100}}', '건강 기록의 하루 누적 거리로 판정'),
(1209, '마라톤 준비 러닝', '하루 동안 달린 거리로 확인해요.', 'EXERCISE', '{"distance_km":{"default":10,"unit":"km","min":1,"max":100}}', '건강 기록의 하루 누적 거리로 판정'),
(1210, '하루 15,000보 챌린지', '하루 동안 걸은 걸음 수로 확인해요.', 'EXERCISE', '{"steps":{"default":15000,"unit":"count","min":1000,"max":100000}}', '건강 기록의 하루 누적 걸음으로 판정');

INSERT INTO `RoutineVerification`
    (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`)
SELECT `id`, 'HEALTH_CONNECT', 'HC_RECORD', 'NONE',
       '["android.permission.health.READ_STEPS","android.permission.health.READ_DISTANCE","ACTIVITY_RECOGNITION"]',
       'SELF_CHECK', 'HEALTH'
FROM `RoutineTemplate` WHERE `id` BETWEEN 1201 AND 1210;

-- ── §4 앱 사용 시간 — 최대형(덜 쓰기) (SCREEN_TIME_MAX) ────────────────
-- 대상 앱은 목표값이 아니라 멤버별 셋업에서 고른다.
INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES
(1301, '인스타그램 줄이기', '하루 앱 사용 시간이 목표 이하인지 확인해요.', 'DETOX', '{"duration_min":{"default":30,"unit":"min","min":0,"max":1440}}', '앱 사용 시간이 목표 이하일 때 성공'),
(1302, '유튜브 줄이기', '하루 앱 사용 시간이 목표 이하인지 확인해요.', 'DETOX', '{"duration_min":{"default":60,"unit":"min","min":0,"max":1440}}', '앱 사용 시간이 목표 이하일 때 성공'),
(1303, '숏폼 끊기', '하루 앱 사용 시간이 목표 이하인지 확인해요.', 'DETOX', '{"duration_min":{"default":20,"unit":"min","min":0,"max":1440}}', '앱 사용 시간이 목표 이하일 때 성공'),
(1304, '게임 시간 줄이기', '하루 앱 사용 시간이 목표 이하인지 확인해요.', 'DETOX', '{"duration_min":{"default":60,"unit":"min","min":0,"max":1440}}', '앱 사용 시간이 목표 이하일 때 성공'),
(1305, '커뮤니티 앱 줄이기', '하루 앱 사용 시간이 목표 이하인지 확인해요.', 'DETOX', '{"duration_min":{"default":30,"unit":"min","min":0,"max":1440}}', '앱 사용 시간이 목표 이하일 때 성공'),
(1306, '쇼핑 앱 안 켜기', '하루 앱 사용 시간이 목표 이하인지 확인해요.', 'FINANCE', '{"duration_min":{"default":10,"unit":"min","min":0,"max":1440}}', '앱 사용 시간이 목표 이하일 때 성공'),
(1307, '메신저 붙잡고 있지 않기', '하루 앱 사용 시간이 목표 이하인지 확인해요.', 'CAREER_PRODUCTIVITY', '{"duration_min":{"default":40,"unit":"min","min":0,"max":1440}}', '앱 사용 시간이 목표 이하일 때 성공'),
(1308, 'OTT 정주행 자제하기', '하루 앱 사용 시간이 목표 이하인지 확인해요.', 'DETOX', '{"duration_min":{"default":60,"unit":"min","min":0,"max":1440}}', '앱 사용 시간이 목표 이하일 때 성공'),
(1309, '배달 앱 안 켜기', '하루 앱 사용 시간이 목표 이하인지 확인해요.', 'DIET_HEALTH', '{"duration_min":{"default":5,"unit":"min","min":0,"max":1440}}', '앱 사용 시간이 목표 이하일 때 성공'),
(1310, '웹툰 몰아보기 줄이기', '하루 앱 사용 시간이 목표 이하인지 확인해요.', 'DETOX', '{"duration_min":{"default":30,"unit":"min","min":0,"max":1440}}', '앱 사용 시간이 목표 이하일 때 성공'),
(1311, 'SNS 전체 사용 줄이기', '고른 SNS 앱들의 하루 합산 사용 시간이 목표 이하인지 확인해요.', 'DETOX', '{"duration_min":{"default":60,"unit":"min","min":0,"max":1440}}', '앱 사용 시간이 목표 이하일 때 성공'),
(1312, '웹소설 보는 시간 줄이기', '하루 앱 사용 시간이 목표 이하인지 확인해요.', 'DETOX', '{"duration_min":{"default":30,"unit":"min","min":0,"max":1440}}', '앱 사용 시간이 목표 이하일 때 성공'),
(1313, '증권·코인 앱 확인 줄이기', '하루 앱 사용 시간이 목표 이하인지 확인해요.', 'FINANCE', '{"duration_min":{"default":20,"unit":"min","min":0,"max":1440}}', '앱 사용 시간이 목표 이하일 때 성공');

INSERT INTO `RoutineVerification`
    (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`)
SELECT `id`, 'PHONE', 'USAGE', 'NONE', '["PACKAGE_USAGE_STATS"]', 'SELF_CHECK', 'SCREEN_TIME_MAX'
FROM `RoutineTemplate` WHERE `id` BETWEEN 1301 AND 1313;

-- ── §5 앱 사용 시간 — 최소형(더 쓰기) (SCREEN_TIME_MIN) ────────────────
INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES
(1401, '매일 외국어 공부하기', '어학 앱을 하루 목표 시간 이상 썼는지 확인해요.', 'STUDY', '{"duration_min":{"default":15,"unit":"min","min":1,"max":1440}}', '앱 사용 시간이 목표 이상일 때 성공'),
(1402, '전자책으로 독서하기', '전자책 앱을 하루 목표 시간 이상 썼는지 확인해요.', 'READING', '{"duration_min":{"default":30,"unit":"min","min":1,"max":1440}}', '앱 사용 시간이 목표 이상일 때 성공'),
(1403, '인강 챙겨 듣기', '강의 앱을 하루 목표 시간 이상 썼는지 확인해요.', 'STUDY', '{"duration_min":{"default":60,"unit":"min","min":1,"max":1440}}', '앱 사용 시간이 목표 이상일 때 성공'),
(1404, '코딩 문제 풀기', '코딩 학습 앱을 하루 목표 시간 이상 썼는지 확인해요.', 'CAREER_PRODUCTIVITY', '{"duration_min":{"default":30,"unit":"min","min":1,"max":1440}}', '앱 사용 시간이 목표 이상일 때 성공'),
(1405, '명상하기', '명상 앱을 하루 목표 시간 이상 썼는지 확인해요.', 'MIND', '{"duration_min":{"default":10,"unit":"min","min":1,"max":1440}}', '앱 사용 시간이 목표 이상일 때 성공'),
(1406, '가계부 쓰기', '가계부 앱을 하루 목표 시간 이상 썼는지 확인해요.', 'FINANCE', '{"duration_min":{"default":5,"unit":"min","min":1,"max":1440}}', '앱 사용 시간이 목표 이상일 때 성공'),
(1407, '홈트 따라 하기', '홈트 앱을 하루 목표 시간 이상 썼는지 확인해요.', 'EXERCISE', '{"duration_min":{"default":20,"unit":"min","min":1,"max":1440}}', '앱 사용 시간이 목표 이상일 때 성공'),
(1408, '악기 연습하기', '연습·튜너 앱을 하루 목표 시간 이상 썼는지 확인해요.', 'HOBBY', '{"duration_min":{"default":20,"unit":"min","min":1,"max":1440}}', '앱 사용 시간이 목표 이상일 때 성공'),
(1409, '뉴스·경제 기사 읽기', '뉴스 앱을 하루 목표 시간 이상 썼는지 확인해요.', 'FINANCE', '{"duration_min":{"default":15,"unit":"min","min":1,"max":1440}}', '앱 사용 시간이 목표 이상일 때 성공'),
(1410, '일기 쓰기', '메모·일기 앱을 하루 목표 시간 이상 썼는지 확인해요.', 'MIND', '{"duration_min":{"default":10,"unit":"min","min":1,"max":1440}}', '앱 사용 시간이 목표 이상일 때 성공'),
(1411, '성경 읽기', '성경 앱을 하루 목표 시간 이상 썼는지 확인해요.', 'MIND', '{"duration_min":{"default":10,"unit":"min","min":1,"max":1440}}', '앱 사용 시간이 목표 이상일 때 성공'),
(1412, '하루 계획 정리하기', '캘린더·할 일 앱을 하루 목표 시간 이상 썼는지 확인해요.', 'CAREER_PRODUCTIVITY', '{"duration_min":{"default":10,"unit":"min","min":1,"max":1440}}', '앱 사용 시간이 목표 이상일 때 성공'),
(1413, '단어 암기하기', '단어장 앱을 하루 목표 시간 이상 썼는지 확인해요.', 'STUDY', '{"duration_min":{"default":15,"unit":"min","min":1,"max":1440}}', '앱 사용 시간이 목표 이상일 때 성공'),
(1414, '자격증 공부하기', '자격증 학습 앱을 하루 목표 시간 이상 썼는지 확인해요.', 'STUDY', '{"duration_min":{"default":30,"unit":"min","min":1,"max":1440}}', '앱 사용 시간이 목표 이상일 때 성공');

INSERT INTO `RoutineVerification`
    (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`)
SELECT `id`, 'PHONE', 'USAGE', 'NONE', '["PACKAGE_USAGE_STATS"]', 'SELF_CHECK', 'SCREEN_TIME_MIN'
FROM `RoutineTemplate` WHERE `id` BETWEEN 1401 AND 1414;

-- ── §6 기상 (WAKE) ─────────────────────────────────────────────────────
-- 하루 첫 잠금 해제 시각 기준 ±10분. 「알람 한 번에 일어나기」는 스누즈 횟수를 알 수 없어 제외.
INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES
(1501, '아침 7시에 일어나기', '하루 첫 휴대폰 잠금 해제 시각으로 기상을 확인해요.', 'WAKE_SLEEP', '{"target_time":{"default":"07:00","unit":"hh:mm"}}', '첫 잠금 해제 시각으로 기상을 판정'),
(1502, '평일 6시 30분 기상', '하루 첫 휴대폰 잠금 해제 시각으로 기상을 확인해요.', 'WAKE_SLEEP', '{"target_time":{"default":"06:30","unit":"hh:mm"}}', '첫 잠금 해제 시각으로 기상을 판정'),
(1503, '미라클 모닝 (5시 기상)', '하루 첫 휴대폰 잠금 해제 시각으로 기상을 확인해요.', 'WAKE_SLEEP', '{"target_time":{"default":"05:00","unit":"hh:mm"}}', '첫 잠금 해제 시각으로 기상을 판정'),
(1504, '주말에도 8시 전 일어나기', '하루 첫 휴대폰 잠금 해제 시각으로 기상을 확인해요.', 'WAKE_SLEEP', '{"target_time":{"default":"08:00","unit":"hh:mm"}}', '첫 잠금 해제 시각으로 기상을 판정'),
(1505, '출근 전 여유 만들기', '하루 첫 휴대폰 잠금 해제 시각으로 기상을 확인해요.', 'CAREER_PRODUCTIVITY', '{"target_time":{"default":"06:00","unit":"hh:mm"}}', '첫 잠금 해제 시각으로 기상을 판정'),
(1506, '아침형 인간 되기 (입문)', '하루 첫 휴대폰 잠금 해제 시각으로 기상을 확인해요.', 'WAKE_SLEEP', '{"target_time":{"default":"08:00","unit":"hh:mm"}}', '첫 잠금 해제 시각으로 기상을 판정'),
(1507, '시험 기간 새벽 공부', '하루 첫 휴대폰 잠금 해제 시각으로 기상을 확인해요.', 'STUDY', '{"target_time":{"default":"05:30","unit":"hh:mm"}}', '첫 잠금 해제 시각으로 기상을 판정');

INSERT INTO `RoutineVerification`
    (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`)
SELECT `id`, 'PHONE', 'USAGE', 'NONE', '["PACKAGE_USAGE_STATS"]', 'SELF_CHECK', 'WAKE'
FROM `RoutineTemplate` WHERE `id` BETWEEN 1501 AND 1507;

-- ── §7 수면 (SLEEP) ────────────────────────────────────────────────────
-- 취침 시각형은 bedtime_before, 수면 시간형은 sleep_hours 를 쓴다.
-- bedtime_before 는 HH:mm(00~23시)만 유효해 "24:00 이전"은 23:59 로 표기한다.
INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES
(1601, '12시 전에 자기', '수면 기록의 잠든 시각으로 확인해요.', 'WAKE_SLEEP', '{"bedtime_before":{"default":"23:59","unit":"hh:mm"}}', '수면 기록의 취침 시각으로 판정'),
(1602, '11시 전에 자기', '수면 기록의 잠든 시각으로 확인해요.', 'WAKE_SLEEP', '{"bedtime_before":{"default":"23:00","unit":"hh:mm"}}', '수면 기록의 취침 시각으로 판정'),
(1603, '새벽 1시 넘기지 않기', '수면 기록의 잠든 시각으로 확인해요.', 'WAKE_SLEEP', '{"bedtime_before":{"default":"01:00","unit":"hh:mm"}}', '수면 기록의 취침 시각으로 판정'),
(1604, '7시간 이상 자기', '수면 기록의 잔 시간으로 확인해요.', 'WAKE_SLEEP', '{"sleep_hours":{"default":7,"unit":"hour","min":3,"max":14}}', '수면 기록의 수면 시간으로 판정'),
(1605, '6시간은 확보하기', '수면 기록의 잔 시간으로 확인해요.', 'WAKE_SLEEP', '{"sleep_hours":{"default":6,"unit":"hour","min":3,"max":14}}', '수면 기록의 수면 시간으로 판정'),
(1606, '규칙적인 수면 습관 만들기', '수면 기록의 잔 시간으로 확인해요.', 'WAKE_SLEEP', '{"sleep_hours":{"default":8,"unit":"hour","min":3,"max":14}}', '수면 기록의 수면 시간으로 판정'),
(1607, '주말 수면 몰아자기 방지', '수면 기록의 잠든 시각으로 확인해요.', 'WAKE_SLEEP', '{"bedtime_before":{"default":"23:59","unit":"hh:mm"}}', '수면 기록의 취침 시각으로 판정'),
(1608, '야근 후에도 6시간 자기', '수면 기록의 잔 시간으로 확인해요.', 'WAKE_SLEEP', '{"sleep_hours":{"default":6,"unit":"hour","min":3,"max":14}}', '수면 기록의 수면 시간으로 판정');

INSERT INTO `RoutineVerification`
    (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`)
SELECT `id`, 'HEALTH_CONNECT', 'SLEEP', 'NONE',
       '["android.permission.health.READ_SLEEP"]', 'SELF_CHECK', 'SLEEP'
FROM `RoutineTemplate` WHERE `id` BETWEEN 1601 AND 1608;
