package com.ruleup.ruleup_backend.challenge.moderation;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.TargetModerationStatus;
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

import java.time.Instant;
import java.util.UUID;

/**
 * 챌린지 제목·설명·이미지 비동기 사후 심사 (기능 스펙 6-1 #4).
 *
 *  - 심사 대상은 항목별 상태가 IN_REVIEW 인 것만 — 사용자 직접 수정분(서버 draftId 대조 판정)과 이미지.
 *  - 제목+설명은 한 세트로 LLM 1회 호출, 결과는 항목별. 세트 심사 1회 = 반복 거부 카운트 1회.
 *  - 명백한 금칙어는 blocklist 로 LLM 호출 전에 즉시 거부(비용 절감·확실한 것만).
 *  - 거부: 대체 표시 유지(타인 = AI 임시 제목·빈 설명·기본 이미지) + 수정 요청 알림.
 *    이미지 거부는 이미지 삭제 + 방장 알림. 1시간 3회 거부 → 1시간 수정 잠금.
 *  - 검수 불가(UNAVAILABLE)는 IN_REVIEW 유지 — 재시도 배치가 수렴시킨다. 기능 제한은 어떤 상태에도 없다
 *    (구 "PENDING_REVIEW 비노출·가입 차단·1시간 미수정 하드 삭제" 플로우 폐기).
 */
@Service
@RequiredArgsConstructor
public class ChallengeModerationService {

    private static final Logger log = LoggerFactory.getLogger(ChallengeModerationService.class);

    private final ChallengeRepository challengeRepository;
    private final ContentModerationClient moderationClient;
    private final ChallengeNameBlocklist blocklist;
    private final NotificationService notificationService;

    @Transactional
    public void moderate(UUID challengeId) {
        Challenge c = challengeRepository.findById(challengeId).orElse(null);
        if (c == null) return;

        Instant now = Instant.now();
        boolean rejected = false;
        rejected |= moderateText(c);
        rejected |= moderateImage(c);
        if (rejected) {
            c.registerModerationRejection(now);
        }
    }

    /** 제목+설명 세트 1회 심사. @return 거부가 하나라도 있었는가 */
    private boolean moderateText(Challenge c) {
        boolean titlePending = c.getModerationTitle() == TargetModerationStatus.IN_REVIEW;
        boolean descriptionPending = c.getModerationDescription() == TargetModerationStatus.IN_REVIEW;
        if (!titlePending && !descriptionPending) return false;

        String title = titlePending ? c.getTitle() : null;
        String description = descriptionPending ? c.getDescription() : null;

        // 명백한 금칙어는 LLM 없이 즉시 거부(blocklist — 애매한 건 LLM 판단에 맡긴다)
        ModerationResult titleVerdict = (title != null && blocklist.hits(title))
                ? ModerationResult.REJECTED : null;
        ModerationResult descriptionVerdict = (description != null && blocklist.hits(description))
                ? ModerationResult.REJECTED : null;

        if ((titlePending && titleVerdict == null) || (descriptionPending && descriptionVerdict == null)) {
            ContentModerationClient.TextVerdicts verdicts = moderationClient.moderateChallengeText(
                    (titlePending && titleVerdict == null) ? title : null,
                    (descriptionPending && descriptionVerdict == null) ? description : null);
            if (titleVerdict == null) titleVerdict = verdicts.title();
            if (descriptionVerdict == null) descriptionVerdict = verdicts.description();
        }

        boolean anyRejected = false;
        if (titlePending) {
            switch (titleVerdict) {
                case APPROVED -> c.approveTitle();
                case REJECTED -> { c.rejectTitle(); anyRejected = true; }
                case UNAVAILABLE -> log.info("챌린지 제목 심사 보류(IN_REVIEW 유지) challengeId={}", c.getId());
            }
        }
        if (descriptionPending) {
            switch (descriptionVerdict) {
                case APPROVED -> c.approveDescription();
                case REJECTED -> { c.rejectDescription(); anyRejected = true; }
                case UNAVAILABLE -> log.info("챌린지 설명 심사 보류(IN_REVIEW 유지) challengeId={}", c.getId());
            }
        }
        if (anyRejected) {
            notificationService.notify(c.getCreatorId(), NotificationType.CHALLENGE_NAME_REJECTED,
                    "챌린지 제목·설명을 바꿔주세요",
                    "[" + c.publicTitle() + "] 챌린지의 제목 또는 설명이 커뮤니티 기준에 맞지 않아요. "
                            + "수정 전까지 다른 사람에게는 임시 제목으로 보여요.");
            log.info("moderation_result target=TEXT approved=false challengeId={}", c.getId());
        } else if (titlePending || descriptionPending) {
            log.info("moderation_result target=TEXT approved=true challengeId={}", c.getId());
        }
        return anyRejected;
    }

    /** 이미지 심사. @return 거부였는가 */
    private boolean moderateImage(Challenge c) {
        if (c.getModerationImage() != TargetModerationStatus.IN_REVIEW) return false;
        String imageUrl = c.getImageUrl();
        if (imageUrl == null || imageUrl.isBlank()) {
            c.approveImage();   // 방어: 심사 중 이미지가 사라졌으면 대상 없음
            return false;
        }
        ModerationResult r = moderationClient.moderateImage(imageUrl);
        switch (r) {
            case APPROVED -> {
                c.approveImage();
                log.info("moderation_result target=IMAGE approved=true challengeId={}", c.getId());
            }
            case REJECTED -> {
                c.rejectAndRemoveImage();
                notificationService.notify(c.getCreatorId(), NotificationType.CHALLENGE_IMAGE_REJECTED,
                        "챌린지 대표 이미지를 바꿔주세요",
                        "[" + c.publicTitle() + "] 챌린지의 대표 이미지가 커뮤니티 기준에 맞지 않아 내렸어요. "
                                + "새 이미지를 올려주세요.");
                log.info("moderation_result target=IMAGE approved=false challengeId={}", c.getId());
                return true;
            }
            case UNAVAILABLE -> log.info("챌린지 이미지 심사 보류(IN_REVIEW 유지) challengeId={}", c.getId());
        }
        return false;
    }
}
