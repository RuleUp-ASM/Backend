package com.ruleup.ruleup_backend.places;

import com.ruleup.ruleup_backend.common.response.ApiResponse;
import com.ruleup.ruleup_backend.places.dto.PlaceSearchResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 장소 검색 프록시(§5.2). NEARBY_BRAND 앵커 바인딩 시 클라가 좌표를 잡는 데 사용. */
@Tag(name = "Places", description = "장소 검색(앵커 바인딩용)")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/places")
@RequiredArgsConstructor
public class PlaceSearchController {

    private final PlaceSearchService placeSearchService;

    @Operation(summary = "장소 검색(§11.7)",
            description = "키워드로 브랜드/매장을 찾아 좌표를 반환. lat/lng는 정렬 힌트(선택), radiusM은 NEARBY_BRAND 반경(기본 1500).")
    @GetMapping("/search")
    public ApiResponse<PlaceSearchResponse> search(@RequestParam("q") String q,
                                                   @RequestParam(required = false) Double lat,
                                                   @RequestParam(required = false) Double lng,
                                                   @RequestParam(required = false) Integer radiusM) {
        return ApiResponse.ok(placeSearchService.search(q, lat, lng, radiusM));
    }
}
