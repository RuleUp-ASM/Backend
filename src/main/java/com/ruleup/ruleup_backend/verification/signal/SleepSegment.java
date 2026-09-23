package com.ruleup.ruleup_backend.verification.signal;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 수면 세그먼트(§2.17). Android Sleep API가 익일 아침 일괄 전달.
 *
 * <p>{@code origin} 은 HEALTH 신호와 같은 신뢰 메타데이터다 — 손으로 입력한 수면 기록을 걸러내려면
 * 기록 방식을 알아야 한다. 아직 보내지 않는 클라가 있어 <b>선택</b>이며, 없으면 통과시키되
 * evidence 에 표시해 실제 전송률을 관측한 뒤 조인다.
 *
 * <p><b>Android 와이어</b>는 세션 단위({@code sessions[]})로 {@code start}·{@code end}(epoch millis)와
 * 평평한 출처({@code recordingMethod}·{@code originPackage})를 싣는다. 여기서 서버 모양으로 접는다.
 * 앱은 stage 를 쪼개지 않으므로 {@code status} 는 비어 온다 — 평가기는 status 를 보지 않는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SleepSegment(String startAt, String endAt, String status, HealthOrigin origin) {
    @JsonCreator
    public static SleepSegment fromWire(
            @JsonProperty("startAt") @JsonAlias("start") String startAt,
            @JsonProperty("endAt") @JsonAlias("end") String endAt,
            @JsonProperty("status") String status,
            @JsonProperty("origin") HealthOrigin origin,
            @JsonProperty("recordingMethod") String recordingMethod,
            @JsonProperty("originPackage") String originPackage) {
        return new SleepSegment(startAt, endAt, status, HealthOrigin.orFlat(origin, originPackage, recordingMethod));
    }
}
