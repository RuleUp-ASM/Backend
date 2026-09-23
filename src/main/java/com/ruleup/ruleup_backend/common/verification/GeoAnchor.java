package com.ruleup.ruleup_backend.common.verification;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 멤버 바인딩 위치 앵커(테크스펙 v2 §5). challenge_members.anchors(JSON 배열)에 저장(PER_MEMBER).
 * 평가는 anchors[] OR("아무 앵커나 dwell 충족"). 핀마다 반경(헬스장 80 vs 카페 100).
 *
 * <p>challenge·verification 공유 커널. JSON 직렬화는 필드명 기반이라 패키지 이동은 저장 데이터에 영향 없음.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GeoAnchor(double lat, double lng, int radiusM, String label) {}
