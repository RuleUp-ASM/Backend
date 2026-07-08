-- 경로: src/main/resources/db/migration/V18__routine_auto_only.sql
-- 루틴 카탈로그를 "자동 인증이 가능한 루틴"만 남긴다.
--   · 정책 변경: 매칭되면(=카탈로그에 있으면) 그 루틴은 자동 인증이 가능한 것으로 본다.
--     매칭 실패는 "루틴을 못 찾음"이 아니라 "자동 인증 불가" → 수동 체크형(SELF_CHECK)으로 폴백.
--   · 따라서 autoVerificationType 이 NULL(자동 불가)인 템플릿은 카탈로그에서 제거한다.
--
-- 삭제 순서: RoutineVerification 이 RoutineTemplate(id) 를 FK 참조(ON DELETE 없음)하므로 자식부터.
DELETE FROM RoutineVerification WHERE autoVerificationType IS NULL;

DELETE t FROM RoutineTemplate t
    LEFT JOIN RoutineVerification v ON v.templateId = t.id
    WHERE v.templateId IS NULL;

-- 수동 체크형(SELF_CHECK)을 manualSignalSource ENUM 에도 넣어 Java SignalSource enum 과 도메인을 맞춘다.
-- (현재 카탈로그엔 SELF_CHECK 행이 없지만, 향후 시드/정합성 대비 값만 허용해 둔다.)
ALTER TABLE RoutineVerification
    MODIFY COLUMN manualSignalSource ENUM('PHOTO','GROUP_CHECK','SELF_CHECK') NOT NULL DEFAULT 'PHOTO';
