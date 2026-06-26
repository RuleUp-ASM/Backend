package com.ruleup.ruleup_backend.places.dto;

import java.util.List;

/**
 * 장소 검색 결과(§5.2 NEARBY_BRAND 앵커 바인딩용). 클라가 이 좌표로 앵커를 만든다.
 *  - 좌표는 WGS84(lat/lng). Naver Local은 mapx/mapy(경도·위도×1e7)를 주므로 변환해 내려준다.
 */
public record PlaceSearchResponse(List<Item> places) {
    public record Item(
            String name,        // 상호(태그 제거)
            String address,     // 지번 주소
            String roadAddress, // 도로명 주소
            String category,    // 분류(있으면)
            double lat,
            double lng
    ) {}
}
