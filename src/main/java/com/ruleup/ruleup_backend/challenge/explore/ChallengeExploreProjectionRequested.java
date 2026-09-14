package com.ruleup.ruleup_backend.challenge.explore;

import java.util.UUID;

/**
 * 탐색 파생 인덱스를 다시 만들어 달라는 요청 (탐색 백엔드 6-2 「설정 변경」).
 *
 * <p>노출 범위·참여 방식·카테고리·인증 방식·티어 컷처럼 <b>탐색이 후보와 필터를 정하는 데
 * 쓰는 값</b>이 바뀌었을 때 커밋 직후 발행한다. 5분 보정만 기다리면 비공개를 공개로 바꾼 방이
 * 그 시간 동안 목록에 안 뜨고, 인증 방식을 바꾼 방은 옛 필터 결과에 계속 뜬다.
 *
 * <p>실패해도 설정 변경을 되돌리지 않는다 — 파생값이고, 늦어도 5분 보정이 따라잡는다.
 */
public record ChallengeExploreProjectionRequested(UUID challengeId) {
}
