package com.ruleup.ruleup_backend.challenge;

import com.ruleup.ruleup_backend.challenge.domain.Anonymity;
import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeModerationStatus;
import com.ruleup.ruleup_backend.challenge.domain.ParticipationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 챌린지 모더레이션 게이트 불변식(CLAUDE.md §5.1) — DB 없이 도메인 레벨로 고정.
 *  - 가시성 게이트는 "이미지" 기준: 이미지 없으면 생성 즉시 APPROVED(공개), 있으면 PENDING_REVIEW(검수 후 공개).
 *  - 비-OWNER는 APPROVED만 가시.
 *  - REJECTED는 1시간 수정창(fixDeadline) 부여, 재제출 시 PENDING_REVIEW로 복귀.
 */
class ChallengeModerationGateTest {

    private Challenge newChallenge(UUID owner, String imageUrl) {
        return Challenge.create(
                owner, "아침 7시 기상", null, imageUrl,
                "WAKE_UP", ParticipationType.SOLO, null, List.of("MON"),
                14, LocalDate.now(),
                null, null, null,
                null, null,
                Anonymity.REAL, true);
    }

    @Test
    @DisplayName("이미지 없으면 즉시 APPROVED, 이미지 있으면 PENDING_REVIEW")
    void create_moderationStatus_dependsOnImage() {
        Challenge noImage = newChallenge(UUID.randomUUID(), null);
        assertThat(noImage.getModerationStatus()).isEqualTo(ChallengeModerationStatus.APPROVED);
        assertThat(noImage.isApproved()).isTrue();

        Challenge withImage = newChallenge(UUID.randomUUID(), "https://cdn.ruleup.com/a.jpg");
        assertThat(withImage.getModerationStatus()).isEqualTo(ChallengeModerationStatus.PENDING_REVIEW);
        assertThat(withImage.isApproved()).isFalse();
    }

    @Test
    @DisplayName("비-OWNER는 APPROVED만 가시(그 외 호출부에서 404), OWNER는 항상 가시")
    void visibility_gate() {
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        Challenge c = newChallenge(owner, "https://cdn.ruleup.com/a.jpg");   // 이미지 있음 → PENDING

        assertThat(c.isVisibleTo(owner)).isTrue();   // OWNER는 PENDING이어도 가시
        assertThat(c.isVisibleTo(other)).isFalse();  // 타인은 미승인 → 비가시(404)

        c.approveModeration(Instant.now());
        assertThat(c.isVisibleTo(other)).isTrue();   // 승인 후 타인도 가시
        assertThat(c.isApproved()).isTrue();
    }

    @Test
    @DisplayName("REJECTED는 fixDeadline 부여, 재제출 시 PENDING_REVIEW로 복귀")
    void reject_then_resubmit() {
        Challenge c = newChallenge(UUID.randomUUID(), "https://cdn.ruleup.com/a.jpg");
        Instant now = Instant.now();
        Instant deadline = now.plusSeconds(3600);

        c.rejectModeration(now, deadline);
        assertThat(c.getModerationStatus()).isEqualTo(ChallengeModerationStatus.REJECTED);
        assertThat(c.getFixDeadline()).isEqualTo(deadline);

        c.resubmitModeration();
        assertThat(c.getModerationStatus()).isEqualTo(ChallengeModerationStatus.PENDING_REVIEW);
        assertThat(c.getFixDeadline()).isNull();
    }
}
