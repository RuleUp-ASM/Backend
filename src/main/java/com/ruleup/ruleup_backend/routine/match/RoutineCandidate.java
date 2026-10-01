package com.ruleup.ruleup_backend.routine.match;

import java.util.List;

/**
 * LLM 에게 후보로 제시하는 템플릿 정보(룩업/목업 테이블 1행).
 * 자동 인증 가능 루틴이 100개 미만이라 전체를 프롬프트에 실어 매칭 정확도를 높인다(고정 프리픽스 = 캐시 대상).
 * 매칭에 도움되는 비민감 필드(이름·카테고리·설명·목표값 키)만 준다 —
 * 인증 방식·신호·권한 같은 민감/결정 값은 서버 카탈로그가 진실이라 LLM 에 노출하지 않는다.
 *
 * <p>{@code paramKeys} 는 "steps(1000~100000)" 처럼 허용 범위를 붙여 준다. 범위를 모르면 LLM 이
 * 제목에 "20보"를 쓰고 서버는 값을 1000 으로 올려 제목과 목표가 어긋난다.
 */
public record RoutineCandidate(long id, String name, String category, String description, List<String> paramKeys) {
}