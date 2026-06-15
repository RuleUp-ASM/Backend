package com.ruleup.ruleup_backend.routine.match;

import java.util.List;

/**
 * LLM 에게 후보로 제시하는 템플릿의 최소 정보.
 * (인증 방식·권한 등 민감 정보는 주지 않는다 — LLM 은 "어떤 루틴인지"와 "목표값"만 다룬다)
 */
public record RoutineCandidate(long id, String name, String category, List<String> paramKeys) {
}