-- ======================================================================
-- routine_catalog — 루틴 템플릿 카탈로그 — 챌린지 생성의 재료이고 사용자에 의존하지 않는다
--
-- 이 파일은 한 도메인의 표를 모아 둔다. 파일 안의 순서는 외래키 방향이고,
-- 파일 사이의 순서도 같다 — 앞 파일만 뒤 파일을 가리킨다.
-- ======================================================================

CREATE TABLE `RoutineTemplate` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `description` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `category` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `paramSchema` json DEFAULT NULL,
  `rationale` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `createdAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updatedAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  FULLTEXT KEY `ftxNameDesc` (`name`,`description`) /*!50100 WITH PARSER `ngram` */ 
) ENGINE=InnoDB AUTO_INCREMENT=1609 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `RoutineVerification` (
  `templateId` bigint unsigned NOT NULL,
  `autoVerificationType` enum('PHONE','HEALTH_CONNECT','EXTERNAL') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `autoSignalSource` enum('GEOFENCE','GPS','ACTIVITY','SLEEP','USAGE','APP_FEATURE','HC_RECORD','EXTERNAL_API') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `autoWearableReq` enum('NONE','OPTIONAL','REQUIRED') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `autoExternalService` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `autoRequiredPermissions` json DEFAULT NULL,
  `manualSignalSource` enum('PHOTO','GROUP_CHECK','SELF_CHECK') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PHOTO',
  `verificationMethod` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`templateId`),
  CONSTRAINT `fkRoutineVerificationTemplate` FOREIGN KEY (`templateId`) REFERENCES `RoutineTemplate` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `RoutineOutcome` (
  `id` binary(16) NOT NULL,
  `userId` binary(16) NOT NULL,
  `challengeId` binary(16) NOT NULL,
  `challengeMemberId` binary(16) NOT NULL,
  `templateId` bigint unsigned DEFAULT NULL,
  `category` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `targetDate` date NOT NULL,
  `status` enum('PENDING','SUCCESS','FAILED','NOT_TARGET','NOT_REQUIRED') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `verifiedVia` enum('AUTO','MANUAL','MANUAL_FALLBACK') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `failureReason` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `confirmedAt` datetime(6) NOT NULL,
  `createdAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updatedAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uqRoutineOutcomeMemberDate` (`challengeId`,`userId`,`targetDate`),
  KEY `ixRoutineOutcomeConfirmedAt` (`confirmedAt`),
  KEY `ixRoutineOutcomeUserDate` (`userId`,`targetDate`,`challengeId`),
  KEY `ixRoutineOutcomeUserStatus` (`userId`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `TemplateStats` (
  `templateId` bigint NOT NULL,
  `usageCount` bigint NOT NULL DEFAULT '0',
  `completedParticipants` bigint NOT NULL DEFAULT '0',
  `completionRate` decimal(5,4) DEFAULT NULL,
  `updatedAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`templateId`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `TemplateSegmentScore` (
  `segmentType` enum('GLOBAL','COUNTRY','GENDER','AGE_BAND','PLATFORM') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `segmentValue` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `templateId` bigint unsigned NOT NULL,
  `score` decimal(12,4) NOT NULL DEFAULT '0.0000',
  `selectionCount` int NOT NULL DEFAULT '0',
  `updatedAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`segmentType`,`segmentValue`,`templateId`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `SegmentTypeWeight` (
  `segmentType` enum('GLOBAL','COUNTRY','GENDER','AGE_BAND','PLATFORM') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `weight` decimal(6,4) NOT NULL DEFAULT '1.0000',
  `sampleSize` bigint NOT NULL DEFAULT '0',
  `updatedAt` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`segmentType`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 기준 데이터 — 코드가 이 행들의 존재를 전제한다.

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1001, '헬스장 가기', '등록한 헬스장에 머문 시간으로 운동 여부를 확인해요.', 'EXERCISE', '{\"duration_min\": {\"max\": 480, \"min\": 10, \"unit\": \"min\", \"default\": 60}}', '위치 체류 시간으로 방문을 판정');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1002, '스터디 카페 가기', '등록한 스터디 카페에 머문 시간으로 공부 시간을 확인해요.', 'STUDY', '{\"duration_min\": {\"max\": 720, \"min\": 10, \"unit\": \"min\", \"default\": 120}}', '위치 체류 시간으로 방문을 판정');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1003, '도서관 가기', '등록한 도서관에 머문 시간으로 방문을 확인해요.', 'STUDY', '{\"duration_min\": {\"max\": 720, \"min\": 10, \"unit\": \"min\", \"default\": 120}}', '위치 체류 시간으로 방문을 판정');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1004, '수영장 가기', '등록한 수영장에 머문 시간으로 운동 여부를 확인해요.', 'EXERCISE', '{\"duration_min\": {\"max\": 480, \"min\": 10, \"unit\": \"min\", \"default\": 60}}', '위치 체류 시간으로 방문을 판정');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1005, '클라이밍장 가기', '등록한 클라이밍장에 머문 시간으로 운동 여부를 확인해요.', 'EXERCISE', '{\"duration_min\": {\"max\": 480, \"min\": 10, \"unit\": \"min\", \"default\": 90}}', '위치 체류 시간으로 방문을 판정');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1006, '필라테스·요가 수업 참석', '등록한 센터에 머문 시간으로 수업 참석을 확인해요.', 'EXERCISE', '{\"duration_min\": {\"max\": 300, \"min\": 10, \"unit\": \"min\", \"default\": 50}}', '위치 체류 시간으로 방문을 판정');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1007, '학원·과외 빠지지 않기', '등록한 학원에 머문 시간으로 출석을 확인해요.', 'STUDY', '{\"duration_min\": {\"max\": 600, \"min\": 10, \"unit\": \"min\", \"default\": 120}}', '위치 체류 시간으로 방문을 판정');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1008, '러닝 크루 모임 참석', '등록한 공원·트랙에 머문 시간으로 모임 참석을 확인해요.', 'EXERCISE', '{\"duration_min\": {\"max\": 300, \"min\": 10, \"unit\": \"min\", \"default\": 60}}', '위치 체류 시간으로 방문을 판정');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1009, '공원 산책하기', '등록한 공원에 머문 시간으로 산책을 확인해요.', 'EXERCISE', '{\"duration_min\": {\"max\": 300, \"min\": 5, \"unit\": \"min\", \"default\": 30}}', '위치 체류 시간으로 방문을 판정');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1010, '코워킹스페이스 출근하기', '등록한 작업 공간에 머문 시간으로 출근을 확인해요.', 'CAREER_PRODUCTIVITY', '{\"duration_min\": {\"max\": 720, \"min\": 30, \"unit\": \"min\", \"default\": 180}}', '위치 체류 시간으로 방문을 판정');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1011, '학교 강의 출석하기', '등록한 강의동에 머문 시간으로 출석을 확인해요.', 'STUDY', '{\"duration_min\": {\"max\": 600, \"min\": 10, \"unit\": \"min\", \"default\": 60}}', '위치 체류 시간으로 방문을 판정');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1012, '병원·재활 치료 다녀오기', '등록한 병원에 머문 시간으로 치료 방문을 확인해요.', 'DIET_HEALTH', '{\"duration_min\": {\"max\": 480, \"min\": 5, \"unit\": \"min\", \"default\": 30}}', '위치 체류 시간으로 방문을 판정');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1013, '동아리·모임 활동 나가기', '등록한 모임 장소에 머문 시간으로 참석을 확인해요.', 'HOBBY', '{\"duration_min\": {\"max\": 600, \"min\": 10, \"unit\": \"min\", \"default\": 120}}', '위치 체류 시간으로 방문을 판정');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1014, '가족·부모님 집 방문하기', '등록한 가족 집에 머문 시간으로 방문을 확인해요.', 'ETC', '{\"duration_min\": {\"max\": 720, \"min\": 10, \"unit\": \"min\", \"default\": 60}}', '위치 체류 시간으로 방문을 판정');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1015, '정기 모임 장소 방문하기', '등록한 모임 공간에 머문 시간으로 참석을 확인해요.', 'HOBBY', '{\"duration_min\": {\"max\": 600, \"min\": 10, \"unit\": \"min\", \"default\": 60}}', '위치 체류 시간으로 방문을 판정');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1101, '술집 안 가기', '등록한 술집에 머물지 않았는지 확인해요.', 'DETOX', '{\"duration_min\": {\"max\": 120, \"min\": 1, \"unit\": \"min\", \"default\": 10}}', '금지 장소 체류로 규칙 위반을 판정');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1102, 'PC방 안 가기', '등록한 PC방에 머물지 않았는지 확인해요.', 'DETOX', '{\"duration_min\": {\"max\": 120, \"min\": 1, \"unit\": \"min\", \"default\": 10}}', '금지 장소 체류로 규칙 위반을 판정');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1103, '야식집 안 가기', '등록한 야식집에 머물지 않았는지 확인해요.', 'DIET_HEALTH', '{\"duration_min\": {\"max\": 120, \"min\": 1, \"unit\": \"min\", \"default\": 10}}', '금지 장소 체류로 규칙 위반을 판정');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1104, '편의점 들르지 않기', '등록한 편의점에 머물지 않았는지 확인해요.', 'DETOX', '{\"duration_min\": {\"max\": 120, \"min\": 1, \"unit\": \"min\", \"default\": 5}}', '금지 장소 체류로 규칙 위반을 판정');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1105, '카페 커피 안 사 마시기', '자주 가던 카페에 머물지 않았는지 확인해요.', 'FINANCE', '{\"duration_min\": {\"max\": 120, \"min\": 1, \"unit\": \"min\", \"default\": 5}}', '금지 장소 체류로 규칙 위반을 판정');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1106, '쇼핑몰 안 가기', '등록한 백화점·아울렛에 머물지 않았는지 확인해요.', 'FINANCE', '{\"duration_min\": {\"max\": 120, \"min\": 1, \"unit\": \"min\", \"default\": 10}}', '금지 장소 체류로 규칙 위반을 판정');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1107, '노래방·클럽 안 가기', '등록한 노래방·클럽에 머물지 않았는지 확인해요.', 'DETOX', '{\"duration_min\": {\"max\": 120, \"min\": 1, \"unit\": \"min\", \"default\": 10}}', '금지 장소 체류로 규칙 위반을 판정');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1108, '흡연 구역 피하기', '등록한 흡연 구역에 머물지 않았는지 확인해요.', 'DETOX', '{\"duration_min\": {\"max\": 120, \"min\": 1, \"unit\": \"min\", \"default\": 5}}', '금지 장소 체류로 규칙 위반을 판정');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1109, '뽑기방·오락실 안 가기', '등록한 인형뽑기방·오락실에 머물지 않았는지 확인해요.', 'FINANCE', '{\"duration_min\": {\"max\": 120, \"min\": 1, \"unit\": \"min\", \"default\": 10}}', '금지 장소 체류로 규칙 위반을 판정');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1110, '야식 배달 픽업 안 가기', '등록한 배달·포장 매장에 머물지 않았는지 확인해요.', 'DIET_HEALTH', '{\"duration_min\": {\"max\": 120, \"min\": 1, \"unit\": \"min\", \"default\": 5}}', '금지 장소 체류로 규칙 위반을 판정');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1111, '디저트·베이커리 가게 안 가기', '등록한 디저트 가게에 머물지 않았는지 확인해요.', 'DIET_HEALTH', '{\"duration_min\": {\"max\": 120, \"min\": 1, \"unit\": \"min\", \"default\": 5}}', '금지 장소 체류로 규칙 위반을 판정');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1112, '카페 들르지 않기', '자주 가던 카페에 머물지 않았는지 확인해요.', 'FINANCE', '{\"duration_min\": {\"max\": 120, \"min\": 1, \"unit\": \"min\", \"default\": 5}}', '금지 장소 체류로 규칙 위반을 판정');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1201, '하루 만 보 걷기', '하루 동안 걸은 걸음 수로 확인해요.', 'EXERCISE', '{\"steps\": {\"max\": 100000, \"min\": 1000, \"unit\": \"count\", \"default\": 10000}}', '건강 기록의 하루 누적 걸음으로 판정');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1202, '하루 6천 보 걷기 (입문)', '하루 동안 걸은 걸음 수로 확인해요.', 'EXERCISE', '{\"steps\": {\"max\": 100000, \"min\": 1000, \"unit\": \"count\", \"default\": 6000}}', '건강 기록의 하루 누적 걸음으로 판정');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1203, '하루 3km 걷기', '하루 동안 이동한 거리로 확인해요.', 'EXERCISE', '{\"distance_km\": {\"max\": 100, \"min\": 1, \"unit\": \"km\", \"default\": 3}}', '건강 기록의 하루 누적 거리로 판정');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1204, '5km 러닝하기', '하루 동안 달린 거리로 확인해요.', 'EXERCISE', '{\"distance_km\": {\"max\": 100, \"min\": 1, \"unit\": \"km\", \"default\": 5}}', '건강 기록의 하루 누적 거리로 판정');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1205, '한 정거장 먼저 내려 걷기', '하루 동안 걸은 걸음 수로 확인해요.', 'EXERCISE', '{\"steps\": {\"max\": 100000, \"min\": 500, \"unit\": \"count\", \"default\": 2000}}', '건강 기록의 하루 누적 걸음으로 판정');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1206, '점심시간 산책하기', '하루 동안 걸은 걸음 수로 확인해요.', 'EXERCISE', '{\"steps\": {\"max\": 100000, \"min\": 500, \"unit\": \"count\", \"default\": 3000}}', '건강 기록의 하루 누적 걸음으로 판정');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1207, '주말 장거리 걷기', '하루 동안 이동한 거리로 확인해요.', 'EXERCISE', '{\"distance_km\": {\"max\": 100, \"min\": 1, \"unit\": \"km\", \"default\": 8}}', '건강 기록의 하루 누적 거리로 판정');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1208, '퇴근 후 동네 한 바퀴', '하루 동안 이동한 거리로 확인해요.', 'EXERCISE', '{\"distance_km\": {\"max\": 100, \"min\": 1, \"unit\": \"km\", \"default\": 2}}', '건강 기록의 하루 누적 거리로 판정');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1209, '마라톤 준비 러닝', '하루 동안 달린 거리로 확인해요.', 'EXERCISE', '{\"distance_km\": {\"max\": 100, \"min\": 1, \"unit\": \"km\", \"default\": 10}}', '건강 기록의 하루 누적 거리로 판정');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1210, '하루 15,000보 챌린지', '하루 동안 걸은 걸음 수로 확인해요.', 'EXERCISE', '{\"steps\": {\"max\": 100000, \"min\": 1000, \"unit\": \"count\", \"default\": 15000}}', '건강 기록의 하루 누적 걸음으로 판정');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1301, '인스타그램 줄이기', '하루 앱 사용 시간이 목표 이하인지 확인해요.', 'DETOX', '{\"duration_min\": {\"max\": 1440, \"min\": 0, \"unit\": \"min\", \"default\": 30}}', '앱 사용 시간이 목표 이하일 때 성공');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1302, '유튜브 줄이기', '하루 앱 사용 시간이 목표 이하인지 확인해요.', 'DETOX', '{\"duration_min\": {\"max\": 1440, \"min\": 0, \"unit\": \"min\", \"default\": 60}}', '앱 사용 시간이 목표 이하일 때 성공');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1303, '숏폼 끊기', '하루 앱 사용 시간이 목표 이하인지 확인해요.', 'DETOX', '{\"duration_min\": {\"max\": 1440, \"min\": 0, \"unit\": \"min\", \"default\": 20}}', '앱 사용 시간이 목표 이하일 때 성공');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1304, '게임 시간 줄이기', '하루 앱 사용 시간이 목표 이하인지 확인해요.', 'DETOX', '{\"duration_min\": {\"max\": 1440, \"min\": 0, \"unit\": \"min\", \"default\": 60}}', '앱 사용 시간이 목표 이하일 때 성공');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1305, '커뮤니티 앱 줄이기', '하루 앱 사용 시간이 목표 이하인지 확인해요.', 'DETOX', '{\"duration_min\": {\"max\": 1440, \"min\": 0, \"unit\": \"min\", \"default\": 30}}', '앱 사용 시간이 목표 이하일 때 성공');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1306, '쇼핑 앱 안 켜기', '하루 앱 사용 시간이 목표 이하인지 확인해요.', 'FINANCE', '{\"duration_min\": {\"max\": 1440, \"min\": 0, \"unit\": \"min\", \"default\": 10}}', '앱 사용 시간이 목표 이하일 때 성공');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1307, '메신저 붙잡고 있지 않기', '하루 앱 사용 시간이 목표 이하인지 확인해요.', 'CAREER_PRODUCTIVITY', '{\"duration_min\": {\"max\": 1440, \"min\": 0, \"unit\": \"min\", \"default\": 40}}', '앱 사용 시간이 목표 이하일 때 성공');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1308, 'OTT 정주행 자제하기', '하루 앱 사용 시간이 목표 이하인지 확인해요.', 'DETOX', '{\"duration_min\": {\"max\": 1440, \"min\": 0, \"unit\": \"min\", \"default\": 60}}', '앱 사용 시간이 목표 이하일 때 성공');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1309, '배달 앱 안 켜기', '하루 앱 사용 시간이 목표 이하인지 확인해요.', 'DIET_HEALTH', '{\"duration_min\": {\"max\": 1440, \"min\": 0, \"unit\": \"min\", \"default\": 5}}', '앱 사용 시간이 목표 이하일 때 성공');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1310, '웹툰 몰아보기 줄이기', '하루 앱 사용 시간이 목표 이하인지 확인해요.', 'DETOX', '{\"duration_min\": {\"max\": 1440, \"min\": 0, \"unit\": \"min\", \"default\": 30}}', '앱 사용 시간이 목표 이하일 때 성공');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1311, 'SNS 전체 사용 줄이기', '고른 SNS 앱들의 하루 합산 사용 시간이 목표 이하인지 확인해요.', 'DETOX', '{\"duration_min\": {\"max\": 1440, \"min\": 0, \"unit\": \"min\", \"default\": 60}}', '앱 사용 시간이 목표 이하일 때 성공');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1312, '웹소설 보는 시간 줄이기', '하루 앱 사용 시간이 목표 이하인지 확인해요.', 'DETOX', '{\"duration_min\": {\"max\": 1440, \"min\": 0, \"unit\": \"min\", \"default\": 30}}', '앱 사용 시간이 목표 이하일 때 성공');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1313, '증권·코인 앱 확인 줄이기', '하루 앱 사용 시간이 목표 이하인지 확인해요.', 'FINANCE', '{\"duration_min\": {\"max\": 1440, \"min\": 0, \"unit\": \"min\", \"default\": 20}}', '앱 사용 시간이 목표 이하일 때 성공');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1401, '매일 외국어 공부하기', '어학 앱을 하루 목표 시간 이상 썼는지 확인해요.', 'STUDY', '{\"duration_min\": {\"max\": 1440, \"min\": 1, \"unit\": \"min\", \"default\": 15}}', '앱 사용 시간이 목표 이상일 때 성공');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1402, '전자책으로 독서하기', '전자책 앱을 하루 목표 시간 이상 썼는지 확인해요.', 'READING', '{\"duration_min\": {\"max\": 1440, \"min\": 1, \"unit\": \"min\", \"default\": 30}}', '앱 사용 시간이 목표 이상일 때 성공');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1403, '인강 챙겨 듣기', '강의 앱을 하루 목표 시간 이상 썼는지 확인해요.', 'STUDY', '{\"duration_min\": {\"max\": 1440, \"min\": 1, \"unit\": \"min\", \"default\": 60}}', '앱 사용 시간이 목표 이상일 때 성공');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1404, '코딩 문제 풀기', '코딩 학습 앱을 하루 목표 시간 이상 썼는지 확인해요.', 'CAREER_PRODUCTIVITY', '{\"duration_min\": {\"max\": 1440, \"min\": 1, \"unit\": \"min\", \"default\": 30}}', '앱 사용 시간이 목표 이상일 때 성공');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1405, '명상하기', '명상 앱을 하루 목표 시간 이상 썼는지 확인해요.', 'MIND', '{\"duration_min\": {\"max\": 1440, \"min\": 1, \"unit\": \"min\", \"default\": 10}}', '앱 사용 시간이 목표 이상일 때 성공');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1406, '가계부 쓰기', '가계부 앱을 하루 목표 시간 이상 썼는지 확인해요.', 'FINANCE', '{\"duration_min\": {\"max\": 1440, \"min\": 1, \"unit\": \"min\", \"default\": 5}}', '앱 사용 시간이 목표 이상일 때 성공');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1407, '홈트 따라 하기', '홈트 앱을 하루 목표 시간 이상 썼는지 확인해요.', 'EXERCISE', '{\"duration_min\": {\"max\": 1440, \"min\": 1, \"unit\": \"min\", \"default\": 20}}', '앱 사용 시간이 목표 이상일 때 성공');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1408, '악기 연습하기', '연습·튜너 앱을 하루 목표 시간 이상 썼는지 확인해요.', 'HOBBY', '{\"duration_min\": {\"max\": 1440, \"min\": 1, \"unit\": \"min\", \"default\": 20}}', '앱 사용 시간이 목표 이상일 때 성공');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1409, '뉴스·경제 기사 읽기', '뉴스 앱을 하루 목표 시간 이상 썼는지 확인해요.', 'FINANCE', '{\"duration_min\": {\"max\": 1440, \"min\": 1, \"unit\": \"min\", \"default\": 15}}', '앱 사용 시간이 목표 이상일 때 성공');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1410, '일기 쓰기', '메모·일기 앱을 하루 목표 시간 이상 썼는지 확인해요.', 'MIND', '{\"duration_min\": {\"max\": 1440, \"min\": 1, \"unit\": \"min\", \"default\": 10}}', '앱 사용 시간이 목표 이상일 때 성공');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1411, '성경 읽기', '성경 앱을 하루 목표 시간 이상 썼는지 확인해요.', 'MIND', '{\"duration_min\": {\"max\": 1440, \"min\": 1, \"unit\": \"min\", \"default\": 10}}', '앱 사용 시간이 목표 이상일 때 성공');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1412, '하루 계획 정리하기', '캘린더·할 일 앱을 하루 목표 시간 이상 썼는지 확인해요.', 'CAREER_PRODUCTIVITY', '{\"duration_min\": {\"max\": 1440, \"min\": 1, \"unit\": \"min\", \"default\": 10}}', '앱 사용 시간이 목표 이상일 때 성공');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1413, '단어 암기하기', '단어장 앱을 하루 목표 시간 이상 썼는지 확인해요.', 'STUDY', '{\"duration_min\": {\"max\": 1440, \"min\": 1, \"unit\": \"min\", \"default\": 15}}', '앱 사용 시간이 목표 이상일 때 성공');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1414, '자격증 공부하기', '자격증 학습 앱을 하루 목표 시간 이상 썼는지 확인해요.', 'STUDY', '{\"duration_min\": {\"max\": 1440, \"min\": 1, \"unit\": \"min\", \"default\": 30}}', '앱 사용 시간이 목표 이상일 때 성공');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1501, '아침 7시에 일어나기', '하루 첫 휴대폰 잠금 해제 시각으로 기상을 확인해요.', 'WAKE_SLEEP', '{\"target_time\": {\"unit\": \"hh:mm\", \"default\": \"07:00\"}}', '첫 잠금 해제 시각으로 기상을 판정');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1502, '평일 6시 30분 기상', '하루 첫 휴대폰 잠금 해제 시각으로 기상을 확인해요.', 'WAKE_SLEEP', '{\"target_time\": {\"unit\": \"hh:mm\", \"default\": \"06:30\"}}', '첫 잠금 해제 시각으로 기상을 판정');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1503, '미라클 모닝 (5시 기상)', '하루 첫 휴대폰 잠금 해제 시각으로 기상을 확인해요.', 'WAKE_SLEEP', '{\"target_time\": {\"unit\": \"hh:mm\", \"default\": \"05:00\"}}', '첫 잠금 해제 시각으로 기상을 판정');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1504, '주말에도 8시 전 일어나기', '하루 첫 휴대폰 잠금 해제 시각으로 기상을 확인해요.', 'WAKE_SLEEP', '{\"target_time\": {\"unit\": \"hh:mm\", \"default\": \"08:00\"}}', '첫 잠금 해제 시각으로 기상을 판정');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1505, '출근 전 여유 만들기', '하루 첫 휴대폰 잠금 해제 시각으로 기상을 확인해요.', 'CAREER_PRODUCTIVITY', '{\"target_time\": {\"unit\": \"hh:mm\", \"default\": \"06:00\"}}', '첫 잠금 해제 시각으로 기상을 판정');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1506, '아침형 인간 되기 (입문)', '하루 첫 휴대폰 잠금 해제 시각으로 기상을 확인해요.', 'WAKE_SLEEP', '{\"target_time\": {\"unit\": \"hh:mm\", \"default\": \"08:00\"}}', '첫 잠금 해제 시각으로 기상을 판정');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1507, '시험 기간 새벽 공부', '하루 첫 휴대폰 잠금 해제 시각으로 기상을 확인해요.', 'STUDY', '{\"target_time\": {\"unit\": \"hh:mm\", \"default\": \"05:30\"}}', '첫 잠금 해제 시각으로 기상을 판정');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1601, '12시 전에 자기', '수면 기록의 잠든 시각으로 확인해요.', 'WAKE_SLEEP', '{\"bedtime_before\": {\"unit\": \"hh:mm\", \"default\": \"23:59\"}}', '수면 기록의 취침 시각으로 판정');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1602, '11시 전에 자기', '수면 기록의 잠든 시각으로 확인해요.', 'WAKE_SLEEP', '{\"bedtime_before\": {\"unit\": \"hh:mm\", \"default\": \"23:00\"}}', '수면 기록의 취침 시각으로 판정');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1603, '새벽 1시 넘기지 않기', '수면 기록의 잠든 시각으로 확인해요.', 'WAKE_SLEEP', '{\"bedtime_before\": {\"unit\": \"hh:mm\", \"default\": \"01:00\"}}', '수면 기록의 취침 시각으로 판정');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1604, '7시간 이상 자기', '수면 기록의 잔 시간으로 확인해요.', 'WAKE_SLEEP', '{\"sleep_hours\": {\"max\": 14, \"min\": 3, \"unit\": \"hour\", \"default\": 7}}', '수면 기록의 수면 시간으로 판정');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1605, '6시간은 확보하기', '수면 기록의 잔 시간으로 확인해요.', 'WAKE_SLEEP', '{\"sleep_hours\": {\"max\": 14, \"min\": 3, \"unit\": \"hour\", \"default\": 6}}', '수면 기록의 수면 시간으로 판정');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1606, '규칙적인 수면 습관 만들기', '수면 기록의 잔 시간으로 확인해요.', 'WAKE_SLEEP', '{\"sleep_hours\": {\"max\": 14, \"min\": 3, \"unit\": \"hour\", \"default\": 8}}', '수면 기록의 수면 시간으로 판정');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1607, '주말 수면 몰아자기 방지', '수면 기록의 잠든 시각으로 확인해요.', 'WAKE_SLEEP', '{\"bedtime_before\": {\"unit\": \"hh:mm\", \"default\": \"23:59\"}}', '수면 기록의 취침 시각으로 판정');

INSERT INTO `RoutineTemplate` (`id`, `name`, `description`, `category`, `paramSchema`, `rationale`) VALUES (1608, '야근 후에도 6시간 자기', '수면 기록의 잔 시간으로 확인해요.', 'WAKE_SLEEP', '{\"sleep_hours\": {\"max\": 14, \"min\": 3, \"unit\": \"hour\", \"default\": 6}}', '수면 기록의 수면 시간으로 판정');

-- 기준 데이터 — 코드가 이 행들의 존재를 전제한다.

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1001, 'PHONE', 'GEOFENCE', 'NONE', NULL, '[\"ACCESS_FINE_LOCATION\", \"ACCESS_BACKGROUND_LOCATION\"]', 'SELF_CHECK', 'GPS_PRESENCE');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1002, 'PHONE', 'GEOFENCE', 'NONE', NULL, '[\"ACCESS_FINE_LOCATION\", \"ACCESS_BACKGROUND_LOCATION\"]', 'SELF_CHECK', 'GPS_PRESENCE');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1003, 'PHONE', 'GEOFENCE', 'NONE', NULL, '[\"ACCESS_FINE_LOCATION\", \"ACCESS_BACKGROUND_LOCATION\"]', 'SELF_CHECK', 'GPS_PRESENCE');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1004, 'PHONE', 'GEOFENCE', 'NONE', NULL, '[\"ACCESS_FINE_LOCATION\", \"ACCESS_BACKGROUND_LOCATION\"]', 'SELF_CHECK', 'GPS_PRESENCE');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1005, 'PHONE', 'GEOFENCE', 'NONE', NULL, '[\"ACCESS_FINE_LOCATION\", \"ACCESS_BACKGROUND_LOCATION\"]', 'SELF_CHECK', 'GPS_PRESENCE');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1006, 'PHONE', 'GEOFENCE', 'NONE', NULL, '[\"ACCESS_FINE_LOCATION\", \"ACCESS_BACKGROUND_LOCATION\"]', 'SELF_CHECK', 'GPS_PRESENCE');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1007, 'PHONE', 'GEOFENCE', 'NONE', NULL, '[\"ACCESS_FINE_LOCATION\", \"ACCESS_BACKGROUND_LOCATION\"]', 'SELF_CHECK', 'GPS_PRESENCE');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1008, 'PHONE', 'GEOFENCE', 'NONE', NULL, '[\"ACCESS_FINE_LOCATION\", \"ACCESS_BACKGROUND_LOCATION\"]', 'SELF_CHECK', 'GPS_PRESENCE');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1009, 'PHONE', 'GEOFENCE', 'NONE', NULL, '[\"ACCESS_FINE_LOCATION\", \"ACCESS_BACKGROUND_LOCATION\"]', 'SELF_CHECK', 'GPS_PRESENCE');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1010, 'PHONE', 'GEOFENCE', 'NONE', NULL, '[\"ACCESS_FINE_LOCATION\", \"ACCESS_BACKGROUND_LOCATION\"]', 'SELF_CHECK', 'GPS_PRESENCE');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1011, 'PHONE', 'GEOFENCE', 'NONE', NULL, '[\"ACCESS_FINE_LOCATION\", \"ACCESS_BACKGROUND_LOCATION\"]', 'SELF_CHECK', 'GPS_PRESENCE');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1012, 'PHONE', 'GEOFENCE', 'NONE', NULL, '[\"ACCESS_FINE_LOCATION\", \"ACCESS_BACKGROUND_LOCATION\"]', 'SELF_CHECK', 'GPS_PRESENCE');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1013, 'PHONE', 'GEOFENCE', 'NONE', NULL, '[\"ACCESS_FINE_LOCATION\", \"ACCESS_BACKGROUND_LOCATION\"]', 'SELF_CHECK', 'GPS_PRESENCE');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1014, 'PHONE', 'GEOFENCE', 'NONE', NULL, '[\"ACCESS_FINE_LOCATION\", \"ACCESS_BACKGROUND_LOCATION\"]', 'SELF_CHECK', 'GPS_PRESENCE');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1015, 'PHONE', 'GEOFENCE', 'NONE', NULL, '[\"ACCESS_FINE_LOCATION\", \"ACCESS_BACKGROUND_LOCATION\"]', 'SELF_CHECK', 'GPS_PRESENCE');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1101, 'PHONE', 'GEOFENCE', 'NONE', NULL, '[\"ACCESS_FINE_LOCATION\", \"ACCESS_BACKGROUND_LOCATION\"]', 'SELF_CHECK', 'GPS_AVOID');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1102, 'PHONE', 'GEOFENCE', 'NONE', NULL, '[\"ACCESS_FINE_LOCATION\", \"ACCESS_BACKGROUND_LOCATION\"]', 'SELF_CHECK', 'GPS_AVOID');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1103, 'PHONE', 'GEOFENCE', 'NONE', NULL, '[\"ACCESS_FINE_LOCATION\", \"ACCESS_BACKGROUND_LOCATION\"]', 'SELF_CHECK', 'GPS_AVOID');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1104, 'PHONE', 'GEOFENCE', 'NONE', NULL, '[\"ACCESS_FINE_LOCATION\", \"ACCESS_BACKGROUND_LOCATION\"]', 'SELF_CHECK', 'GPS_AVOID');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1105, 'PHONE', 'GEOFENCE', 'NONE', NULL, '[\"ACCESS_FINE_LOCATION\", \"ACCESS_BACKGROUND_LOCATION\"]', 'SELF_CHECK', 'GPS_AVOID');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1106, 'PHONE', 'GEOFENCE', 'NONE', NULL, '[\"ACCESS_FINE_LOCATION\", \"ACCESS_BACKGROUND_LOCATION\"]', 'SELF_CHECK', 'GPS_AVOID');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1107, 'PHONE', 'GEOFENCE', 'NONE', NULL, '[\"ACCESS_FINE_LOCATION\", \"ACCESS_BACKGROUND_LOCATION\"]', 'SELF_CHECK', 'GPS_AVOID');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1108, 'PHONE', 'GEOFENCE', 'NONE', NULL, '[\"ACCESS_FINE_LOCATION\", \"ACCESS_BACKGROUND_LOCATION\"]', 'SELF_CHECK', 'GPS_AVOID');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1109, 'PHONE', 'GEOFENCE', 'NONE', NULL, '[\"ACCESS_FINE_LOCATION\", \"ACCESS_BACKGROUND_LOCATION\"]', 'SELF_CHECK', 'GPS_AVOID');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1110, 'PHONE', 'GEOFENCE', 'NONE', NULL, '[\"ACCESS_FINE_LOCATION\", \"ACCESS_BACKGROUND_LOCATION\"]', 'SELF_CHECK', 'GPS_AVOID');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1111, 'PHONE', 'GEOFENCE', 'NONE', NULL, '[\"ACCESS_FINE_LOCATION\", \"ACCESS_BACKGROUND_LOCATION\"]', 'SELF_CHECK', 'GPS_AVOID');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1112, 'PHONE', 'GEOFENCE', 'NONE', NULL, '[\"ACCESS_FINE_LOCATION\", \"ACCESS_BACKGROUND_LOCATION\"]', 'SELF_CHECK', 'GPS_AVOID');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1201, 'HEALTH_CONNECT', 'HC_RECORD', 'NONE', NULL, '[\"android.permission.health.READ_STEPS\", \"android.permission.health.READ_DISTANCE\"]', 'SELF_CHECK', 'HEALTH');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1202, 'HEALTH_CONNECT', 'HC_RECORD', 'NONE', NULL, '[\"android.permission.health.READ_STEPS\", \"android.permission.health.READ_DISTANCE\"]', 'SELF_CHECK', 'HEALTH');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1203, 'HEALTH_CONNECT', 'HC_RECORD', 'NONE', NULL, '[\"android.permission.health.READ_STEPS\", \"android.permission.health.READ_DISTANCE\"]', 'SELF_CHECK', 'HEALTH');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1204, 'HEALTH_CONNECT', 'HC_RECORD', 'NONE', NULL, '[\"android.permission.health.READ_STEPS\", \"android.permission.health.READ_DISTANCE\"]', 'SELF_CHECK', 'HEALTH');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1205, 'HEALTH_CONNECT', 'HC_RECORD', 'NONE', NULL, '[\"android.permission.health.READ_STEPS\", \"android.permission.health.READ_DISTANCE\"]', 'SELF_CHECK', 'HEALTH');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1206, 'HEALTH_CONNECT', 'HC_RECORD', 'NONE', NULL, '[\"android.permission.health.READ_STEPS\", \"android.permission.health.READ_DISTANCE\"]', 'SELF_CHECK', 'HEALTH');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1207, 'HEALTH_CONNECT', 'HC_RECORD', 'NONE', NULL, '[\"android.permission.health.READ_STEPS\", \"android.permission.health.READ_DISTANCE\"]', 'SELF_CHECK', 'HEALTH');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1208, 'HEALTH_CONNECT', 'HC_RECORD', 'NONE', NULL, '[\"android.permission.health.READ_STEPS\", \"android.permission.health.READ_DISTANCE\"]', 'SELF_CHECK', 'HEALTH');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1209, 'HEALTH_CONNECT', 'HC_RECORD', 'NONE', NULL, '[\"android.permission.health.READ_STEPS\", \"android.permission.health.READ_DISTANCE\"]', 'SELF_CHECK', 'HEALTH');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1210, 'HEALTH_CONNECT', 'HC_RECORD', 'NONE', NULL, '[\"android.permission.health.READ_STEPS\", \"android.permission.health.READ_DISTANCE\"]', 'SELF_CHECK', 'HEALTH');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1301, 'PHONE', 'USAGE', 'NONE', NULL, '[\"PACKAGE_USAGE_STATS\"]', 'SELF_CHECK', 'SCREEN_TIME_MAX');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1302, 'PHONE', 'USAGE', 'NONE', NULL, '[\"PACKAGE_USAGE_STATS\"]', 'SELF_CHECK', 'SCREEN_TIME_MAX');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1303, 'PHONE', 'USAGE', 'NONE', NULL, '[\"PACKAGE_USAGE_STATS\"]', 'SELF_CHECK', 'SCREEN_TIME_MAX');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1304, 'PHONE', 'USAGE', 'NONE', NULL, '[\"PACKAGE_USAGE_STATS\"]', 'SELF_CHECK', 'SCREEN_TIME_MAX');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1305, 'PHONE', 'USAGE', 'NONE', NULL, '[\"PACKAGE_USAGE_STATS\"]', 'SELF_CHECK', 'SCREEN_TIME_MAX');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1306, 'PHONE', 'USAGE', 'NONE', NULL, '[\"PACKAGE_USAGE_STATS\"]', 'SELF_CHECK', 'SCREEN_TIME_MAX');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1307, 'PHONE', 'USAGE', 'NONE', NULL, '[\"PACKAGE_USAGE_STATS\"]', 'SELF_CHECK', 'SCREEN_TIME_MAX');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1308, 'PHONE', 'USAGE', 'NONE', NULL, '[\"PACKAGE_USAGE_STATS\"]', 'SELF_CHECK', 'SCREEN_TIME_MAX');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1309, 'PHONE', 'USAGE', 'NONE', NULL, '[\"PACKAGE_USAGE_STATS\"]', 'SELF_CHECK', 'SCREEN_TIME_MAX');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1310, 'PHONE', 'USAGE', 'NONE', NULL, '[\"PACKAGE_USAGE_STATS\"]', 'SELF_CHECK', 'SCREEN_TIME_MAX');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1311, 'PHONE', 'USAGE', 'NONE', NULL, '[\"PACKAGE_USAGE_STATS\"]', 'SELF_CHECK', 'SCREEN_TIME_MAX');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1312, 'PHONE', 'USAGE', 'NONE', NULL, '[\"PACKAGE_USAGE_STATS\"]', 'SELF_CHECK', 'SCREEN_TIME_MAX');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1313, 'PHONE', 'USAGE', 'NONE', NULL, '[\"PACKAGE_USAGE_STATS\"]', 'SELF_CHECK', 'SCREEN_TIME_MAX');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1401, 'PHONE', 'USAGE', 'NONE', NULL, '[\"PACKAGE_USAGE_STATS\"]', 'SELF_CHECK', 'SCREEN_TIME_MIN');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1402, 'PHONE', 'USAGE', 'NONE', NULL, '[\"PACKAGE_USAGE_STATS\"]', 'SELF_CHECK', 'SCREEN_TIME_MIN');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1403, 'PHONE', 'USAGE', 'NONE', NULL, '[\"PACKAGE_USAGE_STATS\"]', 'SELF_CHECK', 'SCREEN_TIME_MIN');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1404, 'PHONE', 'USAGE', 'NONE', NULL, '[\"PACKAGE_USAGE_STATS\"]', 'SELF_CHECK', 'SCREEN_TIME_MIN');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1405, 'PHONE', 'USAGE', 'NONE', NULL, '[\"PACKAGE_USAGE_STATS\"]', 'SELF_CHECK', 'SCREEN_TIME_MIN');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1406, 'PHONE', 'USAGE', 'NONE', NULL, '[\"PACKAGE_USAGE_STATS\"]', 'SELF_CHECK', 'SCREEN_TIME_MIN');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1407, 'PHONE', 'USAGE', 'NONE', NULL, '[\"PACKAGE_USAGE_STATS\"]', 'SELF_CHECK', 'SCREEN_TIME_MIN');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1408, 'PHONE', 'USAGE', 'NONE', NULL, '[\"PACKAGE_USAGE_STATS\"]', 'SELF_CHECK', 'SCREEN_TIME_MIN');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1409, 'PHONE', 'USAGE', 'NONE', NULL, '[\"PACKAGE_USAGE_STATS\"]', 'SELF_CHECK', 'SCREEN_TIME_MIN');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1410, 'PHONE', 'USAGE', 'NONE', NULL, '[\"PACKAGE_USAGE_STATS\"]', 'SELF_CHECK', 'SCREEN_TIME_MIN');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1411, 'PHONE', 'USAGE', 'NONE', NULL, '[\"PACKAGE_USAGE_STATS\"]', 'SELF_CHECK', 'SCREEN_TIME_MIN');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1412, 'PHONE', 'USAGE', 'NONE', NULL, '[\"PACKAGE_USAGE_STATS\"]', 'SELF_CHECK', 'SCREEN_TIME_MIN');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1413, 'PHONE', 'USAGE', 'NONE', NULL, '[\"PACKAGE_USAGE_STATS\"]', 'SELF_CHECK', 'SCREEN_TIME_MIN');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1414, 'PHONE', 'USAGE', 'NONE', NULL, '[\"PACKAGE_USAGE_STATS\"]', 'SELF_CHECK', 'SCREEN_TIME_MIN');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1501, 'PHONE', 'USAGE', 'NONE', NULL, '[\"PACKAGE_USAGE_STATS\"]', 'SELF_CHECK', 'WAKE');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1502, 'PHONE', 'USAGE', 'NONE', NULL, '[\"PACKAGE_USAGE_STATS\"]', 'SELF_CHECK', 'WAKE');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1503, 'PHONE', 'USAGE', 'NONE', NULL, '[\"PACKAGE_USAGE_STATS\"]', 'SELF_CHECK', 'WAKE');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1504, 'PHONE', 'USAGE', 'NONE', NULL, '[\"PACKAGE_USAGE_STATS\"]', 'SELF_CHECK', 'WAKE');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1505, 'PHONE', 'USAGE', 'NONE', NULL, '[\"PACKAGE_USAGE_STATS\"]', 'SELF_CHECK', 'WAKE');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1506, 'PHONE', 'USAGE', 'NONE', NULL, '[\"PACKAGE_USAGE_STATS\"]', 'SELF_CHECK', 'WAKE');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1507, 'PHONE', 'USAGE', 'NONE', NULL, '[\"PACKAGE_USAGE_STATS\"]', 'SELF_CHECK', 'WAKE');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1601, 'HEALTH_CONNECT', 'SLEEP', 'NONE', NULL, '[\"android.permission.health.READ_SLEEP\"]', 'SELF_CHECK', 'SLEEP');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1602, 'HEALTH_CONNECT', 'SLEEP', 'NONE', NULL, '[\"android.permission.health.READ_SLEEP\"]', 'SELF_CHECK', 'SLEEP');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1603, 'HEALTH_CONNECT', 'SLEEP', 'NONE', NULL, '[\"android.permission.health.READ_SLEEP\"]', 'SELF_CHECK', 'SLEEP');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1604, 'HEALTH_CONNECT', 'SLEEP', 'NONE', NULL, '[\"android.permission.health.READ_SLEEP\"]', 'SELF_CHECK', 'SLEEP');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1605, 'HEALTH_CONNECT', 'SLEEP', 'NONE', NULL, '[\"android.permission.health.READ_SLEEP\"]', 'SELF_CHECK', 'SLEEP');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1606, 'HEALTH_CONNECT', 'SLEEP', 'NONE', NULL, '[\"android.permission.health.READ_SLEEP\"]', 'SELF_CHECK', 'SLEEP');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1607, 'HEALTH_CONNECT', 'SLEEP', 'NONE', NULL, '[\"android.permission.health.READ_SLEEP\"]', 'SELF_CHECK', 'SLEEP');

INSERT INTO `RoutineVerification` (`templateId`, `autoVerificationType`, `autoSignalSource`, `autoWearableReq`, `autoExternalService`, `autoRequiredPermissions`, `manualSignalSource`, `verificationMethod`) VALUES (1608, 'HEALTH_CONNECT', 'SLEEP', 'NONE', NULL, '[\"android.permission.health.READ_SLEEP\"]', 'SELF_CHECK', 'SLEEP');
