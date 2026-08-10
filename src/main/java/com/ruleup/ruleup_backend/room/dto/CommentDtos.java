package com.ruleup.ruleup_backend.room.dto;

import java.util.List;

public final class CommentDtos {
    private CommentDtos() {}

    public record CreateRequest(String targetType, String targetId, String body, String parentCommentId) {}
    public record CreateResponse(String commentId, String targetType, String targetId, Author author,
                                 String parentCommentId, String createdAt, boolean notified) {}
    public record ListResponse(List<Comment> comments, String nextCursor) {}
    public record DeleteResponse(boolean removed) {}
    public record Author(String userId, String nickname, String profileImageUrl, boolean blocked) {}
    public record Comment(String commentId, String body, Author author, String parentCommentId,
                          String createdAt, boolean deletable, List<Comment> replies) {}
}
