package com.ruleup.ruleup_backend.challenge.explore;

/**
 * 홈 카테고리 그리드의 집계 대상(PUBLIC + GROUP + ACTIVE)이 바뀌었을 수 있다는 신호.
 *
 * <p>그리드는 "지금 돌아가고 있는 방"만 센다. 그래서 생성(UPCOMING)·가입·탈퇴로는 수가 변하지 않고,
 * 실제로 변하는 순간은 <b>상태 전환(UPCOMING→ACTIVE, ACTIVE→COMPLETED)과 방 삭제</b>뿐이다.
 * 그 세 지점에서만 발행해 캐시를 버린다 — 가입마다 버리면 그리드 쿼리(GROUP BY 전체 스캔)가
 * 트래픽만큼 돌게 된다.
 *
 * @param reason 무엇이 바꿨는지(로그용)
 */
public record ChallengeGridChanged(String reason) {

    public static ChallengeGridChanged of(String reason) {
        return new ChallengeGridChanged(reason);
    }
}
