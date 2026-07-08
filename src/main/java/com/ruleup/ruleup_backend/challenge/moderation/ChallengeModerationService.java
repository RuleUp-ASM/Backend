package com.ruleup.ruleup_backend.challenge.moderation;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeModerationStatus;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.moderation.ContentModerationClient;
import com.ruleup.ruleup_backend.moderation.ModerationResult;
import com.ruleup.ruleup_backend.notification.NotificationService;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 생성/이미지변경 이후 챌린지 "대표 이미지"를 SafeSearch(멀티모달)로 검수하고 결과를 DB에 반영한다 (§5.1).
 *  - 통과   → APPROVED (공개·가입 허용)
 *  - 거절   → REJECTED + 1시간 수정창(fixDeadline) + 생성자에게 "이미지를 바꿔주세요" 알림
 *  - 보류   → PENDING_REVIEW 유지 (AI 막힘 등. 생성은 이미 끝났으니 영향 없음 — 재검 배치가 복구)
 *
 * 가시성 게이트는 "이미지" 기준이다: 이미지 없는 챌린지는 생성 시 이미 APPROVED(즉시 공개)라 여기 오지 않는다.
 * 텍스트(이름)는 별도 LLM 검수 없이 blocklist(동기) + 추천 draft 게이트로 거른다.
 * 생성/수정 자체를 절대 막지 않는다. 여기서 moderationStatus 만 바뀐다.
 * 이미 PENDING_REVIEW 가 아니면(재검수 사이 또 변경 등) 스킵 — 최신 요청만 반영.
 */
@Service
@RequiredArgsConstructor
public class ChallengeModerationService {

    private static final Logger log = LoggerFactory.getLogger(ChallengeModerationService.class);

    /** REJECTED 후 수정 가능 시간(§5.1). */
    private static final Duration FIX_WINDOW = Duration.ofHours(1);

    private final ChallengeRepository challengeRepository;
    private final ContentModerationClient moderationClient;
    private final NotificationService notificationService;

    @Transactional
    public void moderate(UUID challengeId) {
        Challenge c = challengeRepository.findByIdAndDeletedAtIsNull(challengeId).orElse(null);
        if (c == null) return;
        // 검수 대기 상태가 아니면(이미 결정됨/그 사이 또 변경되어 다른 이벤트가 처리 예정) 건너뛴다.
        if (c.getModerationStatus() != ChallengeModerationStatus.PENDING_REVIEW) return;

        Instant now = Instant.now();
        String imageUrl = c.getImageUrl();
        // 방어: 이미지가 없으면 검수 대상이 없으니 즉시 공개(보통 생성 때 이미 APPROVED라 여기 오지 않음).
        if (imageUrl == null || imageUrl.isBlank()) {
            c.approveModeration(now);
            return;
        }

        ModerationResult r = moderationClient.moderateImage(imageUrl);
        switch (r) {
            case APPROVED -> {
                c.approveModeration(now);
                notificationService.notify(c.getCreatorId(), NotificationType.CHALLENGE_APPROVED,
                        "챌린지가 공개되었어요",
                        "[" + c.getTitle() + "] 챌린지가 검수를 통과해 공개되었습니다. 이제 다른 사람이 참여할 수 있어요.");
            }
            case REJECTED -> {
                c.rejectModeration(now, now.plus(FIX_WINDOW));
                notificationService.notify(c.getCreatorId(), NotificationType.CHALLENGE_NAME_REJECTED,
                        "챌린지 대표 이미지를 바꿔주세요",
                        "챌린지 대표 이미지가 커뮤니티 기준에 맞지 않아 공개가 보류되었습니다. "
                                + "1시간 안에 이미지를 바꾸면 다시 검수돼요. 그대로 두면 챌린지가 닫힙니다.");
            }
            case UNAVAILABLE -> log.info("챌린지 이미지 검수 보류(PENDING_REVIEW 유지) challengeId={}", challengeId);
        }
    }
}
