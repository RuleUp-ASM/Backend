package com.ruleup.ruleup_backend.room.controller;

import com.ruleup.ruleup_backend.common.response.ApiResponse;
import com.ruleup.ruleup_backend.room.dto.CrossRankingDtos;
import com.ruleup.ruleup_backend.room.service.CrossRankingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/rankings/challenges")
@RequiredArgsConstructor
public class CrossRankingController {
    private final CrossRankingService service;

    @GetMapping
    public ApiResponse<CrossRankingDtos.Response> get(@RequestParam String mode,
                                                      @RequestParam(required = false) UUID challengeId,
                                                      @RequestParam(required = false) String cursor,
                                                      @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(service.get(mode, challengeId, cursor, size));
    }
}
