-- 감시자 초대 링크를 연 회원 — 수락 전에도 방장이 「누구를 기다리는지」 볼 수 있게 한다.
-- 초대는 사람을 지정하지 않고 링크로 나가므로, 로그인한 회원이 링크를 열었을 때 처음 알 수 있다.
-- 동의(수락)와는 별개다 — 이 값으로는 아무것도 발송하지 않는다.
ALTER TABLE `watcher_invitations`
  ADD COLUMN `opened_by_user_id` binary(16) DEFAULT NULL COMMENT '링크를 마지막으로 연 회원(방장 본인 제외)',
  ADD COLUMN `opened_at` datetime(3) DEFAULT NULL COMMENT '그 시각';
