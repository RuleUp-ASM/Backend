package com.ruleup.ruleup_backend.profile;

import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.moderation.ModerationRequestRepository;
import com.ruleup.ruleup_backend.moderation.UserModerationRequested;
import com.ruleup.ruleup_backend.moderation.domain.ModerationRequest;
import com.ruleup.ruleup_backend.moderation.domain.ModerationRequestStatus;
import com.ruleup.ruleup_backend.moderation.domain.ModerationTarget;
import com.ruleup.ruleup_backend.profile.dto.ProfileImageResponse;
import com.ruleup.ruleup_backend.profile.dto.ProfileResponse;
import com.ruleup.ruleup_backend.profile.dto.UpdateProfileRequest;
import com.ruleup.ruleup_backend.reputation.domain.ReputationScore;
import com.ruleup.ruleup_backend.reputation.ReputationScoreRepository;
import com.ruleup.ruleup_backend.user.domain.InterestCategory;
import com.ruleup.ruleup_backend.user.domain.NicknamePolicy;
import com.ruleup.ruleup_backend.user.domain.NicknameStatus;
import com.ruleup.ruleup_backend.user.domain.User;
import com.ruleup.ruleup_backend.user.NicknameReleaseLockService;
import com.ruleup.ruleup_backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import com.ruleup.ruleup_backend.common.image.ImageStorageService;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProfileService {

    private final UserRepository userRepository;
    private final NicknameReleaseLockService releaseLockService;
    private final ReputationScoreRepository reputationScoreRepository;
    private final ModerationRequestRepository moderationRequestRepository;
    private final ImageStorageService imageStorage;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public ProfileResponse getMyProfile(UUID userId) {
        User user = loadActive(userId);
        return ProfileResponse.from(user, mannerTemp(userId));
    }

    @Transactional
    public ProfileResponse updateProfile(UUID userId, UpdateProfileRequest req) {
        User user = loadActive(userId);
        boolean needsModeration = false;

        // 닉네임: 값이 왔고 현재와 다를 때만 변경
        if (req.nickname() != null && !req.nickname().equals(user.getNickname())) {
            // 변경 주기 제한(월 1회). 단, 모더레이션 거부에 따른 재수정은 횟수에서 제외한다(정책 §3).
            Instant changedAt = user.getNicknameChangedAt();
            boolean fixingRejection = user.getNicknameStatus() == NicknameStatus.REJECTED;
            if (!fixingRejection && changedAt != null
                    && Instant.now().isBefore(changedAt.plus(NicknamePolicy.CHANGE_INTERVAL)))
                throw new BusinessException(ErrorCode.NICKNAME_CHANGE_LOCKED);
            if (!NicknamePolicy.isValid(req.nickname()))
                throw new BusinessException(ErrorCode.NICKNAME_FORMAT_INVALID);
            if (userRepository.isNicknameTaken(req.nickname(), userId))
                throw new BusinessException(ErrorCode.NICKNAME_DUPLICATED);
            // 남이 버린 닉네임의 1주일 잠금(회원 정책 §3). 내가 버린 값을 되돌리는 건 허용된다.
            releaseLockService.requireNotLocked(req.nickname(), userId);

            // 지금 쓰던 닉네임은 이 시점부터 "버려진" 값이 된다 → 1주일간 타인 사용 불가.
            // 승인 대기 중이던 값도 대상이다(타인에게 노출된 적은 없더라도 본인이 고른 값이므로).
            String released = user.getNickname();
            user.changeNickname(req.nickname());   // 상태를 PENDING으로 되돌림 → 재검수 필요
            releaseLockService.release(released, userId);
            releaseLockService.clear(req.nickname());   // 내가 되돌린 값이면 잠금 행을 남기지 않는다
            submitModerationRequest(userId, ModerationTarget.NICKNAME, req.nickname());
            needsModeration = true;
        }

        // 관심 카테고리
        if (req.interestCategories() != null) {
            if (!InterestCategory.isCountValid(req.interestCategories()))
                throw new BusinessException(ErrorCode.CATEGORY_LIMIT_EXCEEDED);
            if (!InterestCategory.allValid(req.interestCategories()))
                throw new BusinessException(ErrorCode.CATEGORY_INVALID);
            user.changeInterestCategories(req.interestCategories());
        }

        // 프로필 이미지 URL (직접 지정 시)
        if (req.profileImageUrl() != null && !req.profileImageUrl().equals(user.getProfileImageUrl())) {
            user.changeProfileImage(req.profileImageUrl());   // 상태 PENDING → 재검수
            submitModerationRequest(userId, ModerationTarget.PROFILE_IMAGE, req.profileImageUrl());
            needsModeration = true;
        }

        if (needsModeration) {
            eventPublisher.publishEvent(new UserModerationRequested(userId));
        }
        return ProfileResponse.from(user, mannerTemp(userId));   // 변경은 커밋 시 자동 반영
    }

    @Transactional
    public ProfileImageResponse uploadImage(UUID userId, MultipartFile image) {
        User user = loadActive(userId);
        String filename = imageStorage.store(image);
        String url = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/files/").path(filename).toUriString();
        user.changeProfileImage(url);   // 상태 PENDING → 커밋 후 재검수
        submitModerationRequest(userId, ModerationTarget.PROFILE_IMAGE, url);
        eventPublisher.publishEvent(new UserModerationRequested(userId));
        // 등록 직후는 항상 PENDING — 심사는 커밋 후 비동기로 돈다(계약: {imageUrl, status})
        return ProfileImageResponse.of(url, user.getProfileImageStatus());
    }

    /**
     * 심사 요청 제출 — 사용자·target당 PENDING 은 하나만(UNIQUE)이므로,
     * 아직 결정되지 않은 기존 요청은 새 제출로 대체(삭제 후 재등록)한다.
     */
    private void submitModerationRequest(UUID userId, ModerationTarget target, String content) {
        moderationRequestRepository
                .findByUserIdAndTargetAndStatus(userId, target, ModerationRequestStatus.PENDING)
                .ifPresent(moderationRequestRepository::delete);
        moderationRequestRepository.flush();   // UNIQUE(user_id, pending_target) 해제 후 INSERT
        moderationRequestRepository.save(ModerationRequest.request(userId, target, content));
    }

    @Transactional
    public void deleteImage(UUID userId) {
        loadActive(userId).removeProfileImage();
    }

    private User loadActive(UUID userId) {
        return userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.LOGIN_REQUIRED));
    }

    private BigDecimal mannerTemp(UUID userId) {
        return reputationScoreRepository.findById(userId)
                .map(ReputationScore::getMannerTemperature)
                .orElse(ReputationScore.INITIAL_TEMPERATURE);
    }
}