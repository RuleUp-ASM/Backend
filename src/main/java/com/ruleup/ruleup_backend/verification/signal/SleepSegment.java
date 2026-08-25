package com.ruleup.ruleup_backend.verification.signal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 수면 세그먼트(§2.17). Android Sleep API가 익일 아침 일괄 전달.
 *
 * <p>{@code origin} 은 HEALTH 신호와 같은 신뢰 메타데이터다 — 손으로 입력한 수면 기록을 걸러내려면
 * 기록 방식을 알아야 한다. 아직 보내지 않는 클라가 있어 <b>선택</b>이며, 없으면 통과시키되
 * evidence 에 표시해 실제 전송률을 관측한 뒤 조인다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SleepSegment(String startAt, String endAt, String status, HealthOrigin origin) {}
