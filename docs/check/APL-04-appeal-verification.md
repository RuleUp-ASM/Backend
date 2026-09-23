# APL-04 — 이의 인용 후 통계·점수 검증

## 정상 신청 구간

귀속일 D의 수행 기간이 끝난 D+1에 `FAIL_EXPECTED`(DB: PENDING)인 인증을 신청한다.
최종 실패 확정과 이의 마감은 모두 **D+2 00:00 KST**다. 정상 흐름에서는
"이미 감점된 FAILED 건을 기한 안에 신청"하는 조합을 만들 수 없다.
확정 실패를 만들기 위해 DB 상태나 마감 시각을 조작하는 것을 통과 조건으로 삼지 않는다.

## 검증 대상과 기대값

자동 인증, 주 7회 목표, BRONZE 가입 점수 10, 사이클 시작 전 가입한 사용자를 기준으로 한다.
사이클 첫날이 미달인 채 다음 날 이의를 신청한다.

- 인증: SUCCESS, verifiedVia=APPEAL, 추가 이의 진입점 제거.
- 연속 기록: 해당 날짜 기준 1일 성공.
- 멤버 집계: success_days=1, fail_days=0, 목표 14일이면 progress_rate=7.14.
- 방 통계: challenge_stats.total_progress_count=1. 표본 기준 미달이면 비율은 null을 유지한다.
- 점수: 커밋 이후 아웃박스에서 사이클 success_count=1과 현재 점수 11로 반영.
  다시 요청하거나 재처리해도 11로 유지되고 원장·요약·사이클 재계산 검증을 통과한다.

`restored.scoreDelta=0`은 **접수 시 동기 지급분**이며 최종 점수 변동이 아니다.
점수 모듈은 구현되어 있고 `SCORE_INPUT` / `APPEAL_SCORE_CORRECTION`을 비동기로 처리한다.
응답만 보고 미반영으로 판정하지 말고, 해당 outbox 처리와 티어 점수를 재조회한다.
과거 확정 판정의 정정·후속 사이클 재계산은 `ScoreReplayIT`에서 별도로 검증한다.

`VerificationAppealIT.acceptedAppealUpdatesStatsAndCycleScore`는 실제 이의 API부터
통계 갱신·아웃박스 소비·사이클·현재 점수까지 연결한다. 점수/원장을 직접 시드하지 않는다.
스테이징의 다른 계정에 원장 불일치가 있으면 [아웃박스 실패 격리](outbox-rollback-recovery.md)도
배포되어야 그 계정의 실패가 정상 계정의 점수 반영을 막지 않는다.
