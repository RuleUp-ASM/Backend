package com.ruleup.ruleup_backend.profile;

import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.moderation.UserModerationRequested;
import com.ruleup.ruleup_backend.profile.dto.ProfileImageResponse;
import com.ruleup.ruleup_backend.profile.dto.ProfileResponse;
import com.ruleup.ruleup_backend.profile.dto.UpdateProfileRequest;
import com.ruleup.ruleup_backend.reputation.domain.ReputationScore;
import com.ruleup.ruleup_backend.reputation.ReputationScoreRepository;
import com.ruleup.ruleup_backend.user.domain.InterestCategory;
import com.ruleup.ruleup_backend.user.domain.NicknamePolicy;
import com.ruleup.ruleup_backend.user.domain.User;
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
    private final ReputationScoreRepository reputationScoreRepository;
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
            Instant changedAt = user.getNicknameChangedAt();
            if (changedAt != null && Instant.now().isBefore(changedAt.plus(NicknamePolicy.CHANGE_INTERVAL)))
                throw new BusinessException(ErrorCode.NICKNAME_CHANGE_LOCKED);
            if (!NicknamePolicy.isValid(req.nickname()))
                throw new BusinessException(ErrorCode.NICKNAME_FORMAT_INVALID);
            if (userRepository.isNicknameTaken(req.nickname(), userId))
                throw new BusinessException(ErrorCode.NICKNAME_DUPLICATED);
            user.changeNickname(req.nickname());   // 상태를 PENDING으로 되돌림 → 재검수 필요
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
        eventPublisher.publishEvent(new UserModerationRequested(userId));
        return new ProfileImageResponse(url);
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