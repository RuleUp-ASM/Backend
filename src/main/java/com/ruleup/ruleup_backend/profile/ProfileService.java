package com.ruleup.ruleup_backend.profile;

import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.profile.dto.ProfileImageResponse;
import com.ruleup.ruleup_backend.profile.dto.ProfileResponse;
import com.ruleup.ruleup_backend.profile.dto.ProfileUpdateResponse;
import com.ruleup.ruleup_backend.profile.dto.UpdateProfileRequest;
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

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProfileService {

    private final UserRepository userRepository;
    private final com.ruleup.ruleup_backend.moderation.UserModerationService moderation;
    private final org.springframework.transaction.support.TransactionTemplate tx;
    private final ImageStorageService imageStorage;


    @Transactional(readOnly = true)
    public ProfileResponse getMyProfile(UUID userId) {
        User user = loadActive(userId);
        return ProfileResponse.from(user);
    }

    /**
     * 프로필 편집(PATCH /users/me/profile) — 닉네임 · 관심 분야 · 사진 삭제.
     *
     * <p>잠금 판정을 <b>가장 먼저</b> 한다. 관심 분야만 바꾸는 요청은 잠금과 무관하므로(자유 변경)
     * 잠금 대상 항목이 실제로 바뀔 때만 검사한다 — 잠긴 사용자가 관심 분야도 못 고치면 안 된다.
     *
     * <p>모더레이션 거부에 따른 재제출은 잠금·횟수 어느 쪽에도 넣지 않는다. 서버가 물린 거부를
     * 사용자 책임으로 셀 수 없기 때문이다. 반대로 <b>거부 횟수만으로 수정을 제한하지도 않는다</b> —
     * 구 {@code MODERATION_LOCKED}(1시간 3회) 는 폐기됐고, 반복 제출은 이상 행위로 기록해
     * 운영 검토로 보낸다(콘텐츠 모더레이션 §1, 오픈 이슈 #8).
     */
    public ProfileUpdateResponse updateProfile(UUID userId, UpdateProfileRequest req) {
        tx.executeWithoutResult(ignored -> updateStoredProfile(userId, req));
        User user = moderation.moderate(userId);
        Instant until = user.profileLockedUntil();
        return new ProfileUpdateResponse(user.getNickname(), user.getNicknameStatus().name(),
                user.getInterestCategories(), until == null ? null : until.toString());
    }

    private void updateStoredProfile(UUID userId, UpdateProfileRequest req) {
        User user = loadLocked(userId);
        Instant now = Instant.now();

        boolean changingNickname = req.nickname() != null && !req.nickname().equals(user.getNickname());
        boolean removingImage = Boolean.TRUE.equals(req.removeProfileImage())
                && user.getProfileImageUrl() != null;
        boolean touchesLocked = changingNickname || removingImage;

        // 잠금은 상태 충돌이지 재시도로 풀리는 게 아니라 409 다(오픈 이슈 #9 — 온보딩 문서와 409 로 통일).
        boolean nicknameRepair = user.getNicknameStatus() == com.ruleup.ruleup_backend.user.domain.NicknameStatus.REJECTED
                || user.getNicknameStatus() == com.ruleup.ruleup_backend.user.domain.NicknameStatus.CONFLICT;
        boolean imageRepair = user.getProfileImageStatus() == com.ruleup.ruleup_backend.user.domain.ProfileImageStatus.REJECTED;
        boolean normalNickname = changingNickname && !nicknameRepair;
        boolean normalImage = removingImage && !imageRepair;
        boolean attachedSave = normalNickname && !normalImage && user.canAttachNicknameToImageSave(now);
        if ((normalNickname || normalImage) && !attachedSave) requireUnlocked(user,now);

        if (changingNickname) {
            if (!NicknamePolicy.isValid(req.nickname()))
                throw new BusinessException(ErrorCode.NICKNAME_FORMAT_INVALID);
            if (userRepository.isNicknameTaken(req.nickname(), userId))
                throw new BusinessException(ErrorCode.NICKNAME_DUPLICATED);
            // 쓰던 닉네임은 여기서 풀리지 않는다 — approved_nickname 이 그대로라 심사 중에는 계속 본인 점유다.
            // 새 닉네임이 승인되는 순간(User#approveNickname) 비로소 이전 값이 해제된다.
            user.changeNickname(req.nickname());   // 상태를 PENDING 으로 되돌림 → 재검수 필요
        }

        if (removingImage) user.removeProfileImage();

        // 관심 분야는 잠금 예외 — 0~6개 전체 교체.
        if (req.interestCategories() != null) {
            if (!InterestCategory.isCountValid(req.interestCategories()))
                throw new BusinessException(ErrorCode.INTEREST_LIMIT_EXCEEDED);
            if (!InterestCategory.allValid(req.interestCategories()))
                throw new BusinessException(ErrorCode.CATEGORY_INVALID);
            user.changeInterestCategories(req.interestCategories());
        }

        if (attachedSave) user.consumeImageSave();
        else if (normalNickname || normalImage) user.startProfileLock(now);
    }

    public ProfileImageResponse uploadImage(UUID userId, MultipartFile image) {
        // Early check avoids uploading known-invalid changes. Recheck under lock after external I/O.
        tx.executeWithoutResult(ignored -> requireImageEditable(loadLocked(userId),Instant.now()));
        String url = imageStorage.urlOf(imageStorage.store(image));
        try {
            tx.executeWithoutResult(ignored -> {
                User user=loadLocked(userId);
                Instant now=Instant.now();
                requireImageEditable(user,now);
                boolean repairing=user.getProfileImageStatus()==com.ruleup.ruleup_backend.user.domain.ProfileImageStatus.REJECTED;
                user.registerProfileImage(now,repairing);
                user.changeProfileImage(url);
            });
        } catch (RuntimeException failed) {
            try { imageStorage.deleteByUrl(url); } catch (RuntimeException cleanup) { failed.addSuppressed(cleanup); }
            throw failed;
        }
        User reviewed=moderation.moderate(userId);
        return ProfileImageResponse.of(reviewed.visibleProfileImageTo(userId),reviewed.getProfileImageStatus());
    }

    public void deleteImage(UUID userId) {
        tx.executeWithoutResult(ignored -> updateStoredProfile(userId,new UpdateProfileRequest(null,null,true)));
    }

    private void requireImageEditable(User user,Instant now) {
        if (user.getProfileImageStatus()!=com.ruleup.ruleup_backend.user.domain.ProfileImageStatus.REJECTED) requireUnlocked(user,now);
    }

    private void requireUnlocked(User user,Instant now) {
        if (user.isProfileLocked(now)) throw BusinessException.profileChangeLocked(user.profileLockedUntil().toString());
    }

    private User loadLocked(UUID userId) {
        return userRepository.findByIdForUpdate(userId).filter(user -> !user.isWithdrawn())
                .orElseThrow(() -> new BusinessException(ErrorCode.LOGIN_REQUIRED));
    }

    private User loadActive(UUID userId) {
        return userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.LOGIN_REQUIRED));
    }

}