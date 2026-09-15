package com.ruleup.ruleup_backend.challenge.moderation;

import java.util.List;
import java.util.UUID;

/** 콘텐츠는 소비 중 메모리에만 존재한다. SQS payload에는 id와 targets만 전달한다. */
public record ChallengeModerationSnapshot(UUID id, UUID ownerId, String title, String description,
                                          String image, List<Target> targets) {
    public enum Target {
        TITLE("title", "moderation_title"),
        DESCRIPTION("description", "moderation_description"),
        IMAGE("image_url", "moderation_image");
        final String column;
        final String statusColumn;
        Target(String column, String statusColumn) { this.column = column; this.statusColumn = statusColumn; }
    }
    public String content(Target target) {
        return switch (target) { case TITLE -> title; case DESCRIPTION -> description; case IMAGE -> image; };
    }
}
