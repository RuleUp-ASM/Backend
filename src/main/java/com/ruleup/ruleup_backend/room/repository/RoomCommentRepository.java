package com.ruleup.ruleup_backend.room.repository;

import com.ruleup.ruleup_backend.room.domain.CommentTargetType;
import com.ruleup.ruleup_backend.room.domain.RoomComment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoomCommentRepository extends JpaRepository<RoomComment, UUID> {
    Optional<RoomComment> findByIdAndDeletedAtIsNull(UUID id);
    List<RoomComment> findByTargetTypeAndTargetIdAndDeletedAtIsNullOrderByCreatedAtAscIdAsc(
            CommentTargetType targetType, UUID targetId);
    List<RoomComment> findByParentCommentIdAndDeletedAtIsNull(UUID parentCommentId);
    long countByTargetTypeAndTargetIdAndDeletedAtIsNull(CommentTargetType targetType, UUID targetId);
}
