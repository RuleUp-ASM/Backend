package com.ruleup.ruleup_backend.verification.signal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * Health Connect 레코드 1건. 움직임(거리·걸음·운동시간) 판정의 원천.
 *  - recordId          : Health Connect 레코드 식별자(선택). 같은 레코드의 재전송을 서버가 알아본다
 *  - metric            : DISTANCE|STEPS|EXERCISE_DURATION
 *  - value/unit        : 5.2 / "km|count|min"
 *  - startTime/endTime : 이 값이 측정된 <b>구간</b>(ISO). 서버가 겹치는 구간을 걷어내고 합산한다
 *  - exerciseType      : RUNNING 등(null=무관)
 *  - origin            : 신뢰 메타데이터(필수). 없으면 서버 거부(§6.3).
 *
 * <p><b>구간이 왜 필요한가.</b> 스펙은 "시간 구간이 겹치는 레코드는 중복 구간을 제거한 뒤
 * 누적"하라고 적었다. 폰과 워치가 같은 산책을 각자 기록하면 두 레코드의 구간이 겹치는데,
 * 구간이 없으면 서버는 그 둘을 구분할 방법이 없어 그냥 더하거나(두 배) 큰 쪽만 쓰게 된다
 * (작은 쪽을 통째로 버린다). 둘 다 틀린 값이다.
 *
 * <p>구간을 보내지 않는 클라가 아직 있어 <b>선택</b>이다. 구간 없는 레코드는 예전처럼
 * "그날 누적값"으로 다뤄 가장 큰 값을 쓴다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HealthReading(
        String recordId,
        String metric,
        BigDecimal value,
        String unit,
        String startTime,
        String endTime,
        String exerciseType,
        HealthOrigin origin
) {}
