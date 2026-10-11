package com.ruleup.ruleup_backend.observability.metric;

import java.util.Map;
import java.util.TreeMap;

/**
 * CloudWatch 시계열 하나 — 네임스페이스·이름·차원·단위가 같으면 같은 시계열이다.
 *
 * <p>차원은 이름순으로 고정한다. 같은 조합을 다른 순서로 만들면 맵 키가 갈라져 한 분에 두 데이터포인트가 생긴다.
 * <b>값이 열린 차원(userId·requestId·원본 URL·TaskId)은 넣지 않는다</b> — 값 하나가 곧 월 과금 시계열 하나다
 * (노션 「운영 경보 상세」 §4 지표 차원).
 */
public record MetricKey(String namespace, String name, Map<String, String> dimensions, Unit unit) {

    public enum Unit { COUNT, MILLISECONDS, PERCENT, SECONDS, NONE }

    public MetricKey {
        dimensions = Map.copyOf(new TreeMap<>(dimensions));
    }

    public static MetricKey count(String namespace, String name, Map<String, String> dimensions) {
        return new MetricKey(namespace, name, dimensions, Unit.COUNT);
    }

    /** 정렬된 차원 — 전송 순서를 고정해 시험이 비교하기 쉽게. */
    public TreeMap<String, String> sortedDimensions() {
        return new TreeMap<>(dimensions);
    }
}
