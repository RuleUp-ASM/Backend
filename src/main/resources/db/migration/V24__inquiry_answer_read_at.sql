-- 답변을 유저가 상세에서 열어 본 시각. 목록의 새 답변 배지가 이 값으로 갈린다 — 상태(ANSWERED)만으로는
-- 이미 읽은 답변과 새 답변을 구분할 수 없어, 한 번 답변된 문의가 영영 새 답변으로 보였다(QA 10/5).
ALTER TABLE `inquiries`
  ADD COLUMN `answer_read_at` datetime(3) DEFAULT NULL
    COMMENT '유저가 답변을 처음 열어 본 시각 — NULL 이고 답변돼 있으면 새 답변' AFTER `answered_by`;

-- 기존 답변 건은 읽은 것으로 둔다. 배포 직후 지난 답변이 한꺼번에 새 답변으로 뜨는 것이 이번 결함이다.
UPDATE `inquiries` SET `answer_read_at` = `answered_at` WHERE `status` = 'ANSWERED';
