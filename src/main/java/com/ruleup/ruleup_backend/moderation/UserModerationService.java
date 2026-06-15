package com.ruleup.ruleup_backend.moderation;

import com.ruleup.ruleup_backend.notification.NotificationService;
import com.ruleup.ruleup_backend.notification.NotificationType;
import com.ruleup.ruleup_backend.user.User;
import com.ruleup.ruleup_backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 가입/변경 이후 닉네임·프로필 사진을 LLM으로 검수하고 그 결과를 DB에 반영한다.
 *  - 통과   → APPROVED (타인에게도 노출)
 *  - 거절   → REJECTED + "바꿔주세요" 알림 (타인에게는 임시 닉네임/숨김)
 *  - 보류   → PENDING 유지 (AI 막힘 등. 가입은 이미 끝났으니 영향 없음)
 *
 * 가입 자체를 절대 막지 않는다. 여기서 DB 값만 바뀐다.
 */
@Service
@RequiredArgsConstructor
public class UserModerationService {

    private static final Logger log = LoggerFactory.getLogger(UserModerationService.class);

    private final UserRepository userRepository;
    private final ContentModerationClient moderationClient;
    private final NotificationService notificationService;

    @Transactional
    public void moderate(UUID userId) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId).orElse(null);
        if (user == null) return;

        boolean checked = false;

        // ===== 닉네임 검수 =====
        if (user.isNicknamePending()) {
            ModerationResult r = moderationClient.moderateNickname(user.getNickname());
            switch (r) {
                case APPROVED -> { user.approveNickname(); checked = true; }
                case REJECTED -> {
                    user.rejectNickname();
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
                case APPROVED -> { user.approveProfileImage(); checked = true; }
                case REJECTED -> {
                    user.rejectProfileImage();
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
}
