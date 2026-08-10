package com.ruleup.ruleup_backend.room.controller;

import com.ruleup.ruleup_backend.common.response.ApiResponse;
import com.ruleup.ruleup_backend.room.dto.CommentDtos;
import com.ruleup.ruleup_backend.room.service.CommentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/comments")
@RequiredArgsConstructor
public class CommentController {
    private final CommentService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CommentDtos.CreateResponse> create(@AuthenticationPrincipal String userId,
                                                          @RequestBody CommentDtos.CreateRequest request) {
        return ApiResponse.ok(service.create(UUID.fromString(userId), request));
    }

    @GetMapping
    public ApiResponse<CommentDtos.ListResponse> list(@AuthenticationPrincipal String userId,
                                                      @RequestParam String targetType,
                                                      @RequestParam String targetId,
                                                      @RequestParam(required = false) String cursor,
                                                      @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(service.list(UUID.fromString(userId), targetType, targetId, cursor, size));
    }

    @DeleteMapping("/{commentId}")
    public ApiResponse<CommentDtos.DeleteResponse> delete(@AuthenticationPrincipal String userId,
                                                          @PathVariable UUID commentId) {
        return ApiResponse.ok(service.delete(UUID.fromString(userId), commentId));
    }
}
