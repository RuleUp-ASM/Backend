package com.ruleup.ruleup_backend.room.controller;

import com.ruleup.ruleup_backend.common.response.ApiResponse;
import com.ruleup.ruleup_backend.room.dto.ThreadDtos;
import com.ruleup.ruleup_backend.room.service.ThreadService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/challenges/{challengeId}/threads")
@RequiredArgsConstructor
public class ThreadController {
    private final ThreadService service;

    @GetMapping
    public ApiResponse<ThreadDtos.Response> get(@AuthenticationPrincipal String userId,
                                                @PathVariable UUID challengeId,
                                                @RequestParam(required = false) String cursor,
                                                @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(service.get(UUID.fromString(userId), challengeId, cursor, size));
    }
}
