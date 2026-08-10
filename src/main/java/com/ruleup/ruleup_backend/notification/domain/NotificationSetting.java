package com.ruleup.ruleup_backend.notification.domain;

import com.ruleup.ruleup_backend.common.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "notification_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationSetting extends AssignedIdEntity {
    @Id @JdbcTypeCode(SqlTypes.BINARY) @Column(name = "user_id") private UUID userId;
    @Column(name = "challenge_activity") private boolean challengeActivity = true;
    @Column(name = "room_activity") private boolean roomActivity = true;
    @Column(name = "tier_activity") private boolean tierActivity = true;
    private boolean marketing;
    @Column(name = "night_push") private boolean nightPush;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "muted_challenge_ids") private List<String> mutedChallengeIds = new ArrayList<>();

    public static NotificationSetting defaults(UUID userId) {
        NotificationSetting setting = new NotificationSetting();
        setting.userId = userId;
        return setting;
    }

    @Override
    public UUID getId() { return userId; }

    public void patch(Boolean challengeActivity, Boolean roomActivity, Boolean tierActivity,
                      Boolean marketing, Boolean nightPush, List<String> mutedChallengeIds) {
        if (challengeActivity != null) this.challengeActivity = challengeActivity;
        if (roomActivity != null) this.roomActivity = roomActivity;
        if (tierActivity != null) this.tierActivity = tierActivity;
        if (marketing != null) this.marketing = marketing;
        if (nightPush != null) this.nightPush = nightPush;
        if (mutedChallengeIds != null) {
            for (String id : mutedChallengeIds) UUID.fromString(id);
            this.mutedChallengeIds = new ArrayList<>(mutedChallengeIds);
        }
    }
}
