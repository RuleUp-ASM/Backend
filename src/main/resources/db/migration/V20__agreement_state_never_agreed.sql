-- 「한 번도 동의한 적 없음」을 version·agreed_at NULL 로 표현한다(동의 상태 조회 API 스펙).
-- 가입 때 미동의한 선택 약관도 두 칸을 가입 시각·현행 버전으로 채워, 앱이 「동의 후 철회」와
-- 구분할 수 없었다(QA ONB-17). 철회는 동의했던 버전·시각을 그대로 둔다.
ALTER TABLE `user_agreement_states`
  MODIFY `version` varchar(16) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  MODIFY `agreed_at` datetime(3) DEFAULT NULL;

-- 기존 행 보정: 지금 미동의이고 이력에 동의가 한 번도 없으면 동의한 적 없는 것이다.
UPDATE `user_agreement_states` s
   SET s.`version` = NULL, s.`agreed_at` = NULL
 WHERE s.`agreed` = 0
   AND NOT EXISTS (SELECT 1 FROM `user_agreement_events` e
                    WHERE e.`user_id` = s.`user_id`
                      AND e.`agreement_type` = s.`agreement_type`
                      AND e.`agreed` = 1);
