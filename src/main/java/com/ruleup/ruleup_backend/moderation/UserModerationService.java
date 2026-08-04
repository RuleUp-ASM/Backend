package com.ruleup.ruleup_backend.moderation;

import com.ruleup.ruleup_backend.moderation.domain.ModerationRequest;
import com.ruleup.ruleup_backend.moderation.domain.ModerationRequestStatus;
import com.ruleup.ruleup_backend.moderation.domain.ModerationTarget;
import com.ruleup.ruleup_backend.notification.NotificationService;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import com.ruleup.ruleup_backend.user.domain.User;
import com.ruleup.ruleup_backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 가입/변경 이후 닉네임·프로필 사진을 LLM으로 검수하고 그 결과를 DB에 반영한다.
 *  - 통과   → APPROVED (approved_* 갱신 → 타인에게도 노출). 단, 승인 직전 선점 재검사에서
 *             충돌하면 CONFLICT (DB 정리 §7.2 — 큐 대기 중 다른 요청이 먼저 승인된 경우)
 *  - 거절   → REJECTED + "바꿔주세요" 알림 (타인에게는 직전 승인본/임시 닉네임 유지)
 *  - 보류   → PENDING 유지 (AI 막힘 등. 가입은 이미 끝났으니 영향 없음)
 * 결정은 moderation_requests 이력에도 함께 기록한다.
 *
 * 가입 자체를 절대 막지 않는다. 여기서 DB 값만 바뀐다.
 */
@Service
@RequiredArgsConstructor
public class UserModerationService {

    private static final Logger log = LoggerFactory.getLogger(UserModerationService.class);

    private final UserRepository userRepository;
    private final ModerationRequestRepository moderationRequestRepository;
    private final ContentModerationClient moderationClient;
    private final NotificationService notificationService;

    @Transactional
    public void moderate(UUID userId) {
        // 커밋 직후 비동기로 도는 사이 사용자가 탈퇴했을 수 있다 → 그러면 검수할 대상이 없다.
        // (엔티티는 @DynamicUpdate 라 여기서 상태 컬럼을 되돌리지는 않는다)
        User user = userRepository.findByIdAndDeletedAtIsNull(userId).orElse(null);
        if (user == null) return;

        boolean checked = false;

        // ===== 닉네임 검수 =====
        if (user.isNicknamePending()) {
            ModerationResult r = moderationClient.moderateNickname(user.getNickname());
            switch (r) {
                case APPROVED -> {
                    // 승인 직전 선점 재검사 — 심사 대기 중 타인이 같은 닉네임을 먼저 승인받았을 수 있다
                    if (userRepository.isNicknameTaken(user.getNickname(), userId)) {
                        user.markNicknameConflict();
                        decideRequest(userId, ModerationTarget.NICKNAME, false, "닉네임 선점 충돌(CONFLICT)");
                    } else {
                        user.approveNickname();
                        decideRequest(userId, ModerationTarget.NICKNAME, true, null);
                    }
                    checked = true;
                }
                case REJECTED -> {
                    user.rejectNickname();
                    decideRequest(userId, ModerationTarget.NICKNAME, false, "커뮤니티 기준 위반");
                    checked = true;
                    notificationService.notify(userId, NotificationType.NICKNAME_REJECTED,
                            "닉네임을 바꿔주세요",
                            "회원님의 닉네임이 커뮤니티 기준에 맞지 않아 다른 사용자에게는 임시 닉네임으로 표시됩니다. "
                                    + "닉네임을 변경하면 다시 노출됩니다.");
                }
                case UNAVAILABLE -> log.info("닉네임 검수 보류(PENDING 유지) userId={}", userId);
            }
        }

        // ===== 프로필 사진 검수 =====
        if (user.isProfileImagePending()) {
            ModerationResult r = moderationClient.moderateImage(user.getProfileImageUrl());
            switch (r) {
                case APPROVED -> {
                    user.approveProfileImage();
                    decideRequest(userId, ModerationTarget.PROFILE_IMAGE, true, null);
                    checked = true;
                }
                case REJECTED -> {
                    user.rejectProfileImage();
                    decideRequest(userId, ModerationTarget.PROFILE_IMAGE, false, "커뮤니티 기준 위반");
                    checked = true;
                    notificationService.notify(userId, NotificationType.PROFILE_IMAGE_REJECTED,
                            "프로필 사진을 바꿔주세요",
                            "회원님의 프로필 사진이 커뮤니티 기준에 맞지 않아 다른 사용자에게는 숨겨집니다. "
                                    + "사진을 변경하면 다시 노출됩니다.");
                }
                case UNAVAILABLE -> log.info("사진 검수 보류(PENDING 유지) userId={}", userId);
            }
        }

        if (checked) user.markModerationChecked();
    }

    /** PENDING 심사 요청에 결정을 기록(이력 보존). 요청 행이 없어도 검수 자체는 유효하다. */
    private void decideRequest(UUID userId, ModerationTarget target, boolean approved, String reason) {
        moderationRequestRepository
                .findByUserIdAndTargetAndStatus(userId, target, ModerationRequestStatus.PENDING)
                .ifPresent(req -> {
                    if (approved) req.approve();
                    else req.reject(reason);
                });
    }
}
