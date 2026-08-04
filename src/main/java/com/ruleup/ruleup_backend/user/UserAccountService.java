package com.ruleup.ruleup_backend.user;

import com.ruleup.ruleup_backend.agreement.UserAgreementRepository;
import com.ruleup.ruleup_backend.agreement.domain.AgreementType;
import com.ruleup.ruleup_backend.agreement.domain.UserAgreement;
import com.ruleup.ruleup_backend.auth.RefreshTokenRepository;
import com.ruleup.ruleup_backend.auth.dto.UserResponse;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.score.UserScoreSummaryRepository;
import com.ruleup.ruleup_backend.score.domain.UserScoreSummary;
import com.ruleup.ruleup_backend.user.domain.User;
import com.ruleup.ruleup_backend.user.dto.UserMeResponse;
import com.ruleup.ruleup_backend.user.dto.WithdrawResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 회원 계정 관리 — 탈퇴(소프트)·내 프로필 조회.
 * 탈퇴(회원 정책 §6 · DB 정리 §12.1): status=WITHDRAWN + deleted_at, 기기 연결 해제,
 * RT 전부 revoke. 개인정보 S3 아카이브·1년 파기 배치·참여 챌린지 자동 탈퇴는 후속 이슈
 * (기능 스펙 6-2 #6 — W1은 deleted_at 기록까지).
 */
@Service
@RequiredArgsConstructor
public class UserAccountService {

    /** 탈퇴 확인 문구 — 서버 검증 문자열로 계약의 일부. */
    static final String CONFIRM_PHRASE = "탈퇴할게요";
    /** 개인정보 아카이브 보존 기간(탈퇴 후 복원 가능 기간). */
    private static final long ARCHIVE_RETENTION_DAYS = 365;
    private static final String RESTORE_NOTE = "1년 안에 같은 소셜 계정으로 로그인하면 기록이 복원돼요";

    /** agreements 응답 키(계약) ↔ 저장 enum 매핑. */
    private static final Map<AgreementType, String> AGREEMENT_KEYS = new LinkedHashMap<>() {{
        put(AgreementType.TOS, "termsOfService");
        put(AgreementType.PRIVACY, "privacyPolicy");
        put(AgreementType.LOCATION, "locationService");
        put(AgreementType.MARKETING, "marketing");
        put(AgreementType.EVENT, "event");
        put(AgreementType.NIGHT_PUSH, "nightPush");
    }};

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserAgreementRepository userAgreementRepository;
    private final UserScoreSummaryRepository scoreSummaryRepository;

    /** 회원 탈퇴 — 멱등(이미 탈퇴 상태면 무해하게 같은 응답). BANNED는 제재 세탁 방지를 위해 불가. */
    @Transactional
    public WithdrawResponse withdraw(UUID userId, String confirmPhrase) {
        if (!CONFIRM_PHRASE.equals(confirmPhrase))
            throw new BusinessException(ErrorCode.CONFIRM_PHRASE_MISMATCH);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.LOGIN_REQUIRED));
        if (user.isBanned()) throw new BusinessException(ErrorCode.ACCOUNT_BANNED);

        if (!user.isWithdrawn()) {
            user.withdraw();                                                    // WITHDRAWN + deleted_at + 기기 해제
            refreshTokenRepository.revokeAllByUserId(userId, Instant.now());    // 전 세션 종료
        }
        Instant archiveExpiresAt = user.getDeletedAt().plus(ARCHIVE_RETENTION_DAYS, ChronoUnit.DAYS);
        return new WithdrawResponse(true, archiveExpiresAt.toString(), RESTORE_NOTE);
    }

    /** 내 프로필 조회 — user 블록(로그인 응답과 동일) + 생일·성별·약관 6종 현재 상태. */
    @Transactional(readOnly = true)
    public UserMeResponse me(UUID userId) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.LOGIN_REQUIRED));
        UserScoreSummary summary = scoreSummaryRepository.findById(userId).orElse(null);

        // 약관 현재 상태 = 타입별 최신 행 (append-only 이력)
        List<UserAgreement> history = userAgreementRepository.findByUser_IdOrderByCreatedAtDescIdDesc(userId);
        Map<String, UserMeResponse.AgreementState> agreements = new LinkedHashMap<>();
        AGREEMENT_KEYS.forEach((type, key) -> history.stream()
                .filter(a -> a.getAgreementType() == type)
                .findFirst()   // 최신순 정렬이므로 첫 행이 현재 상태
                .ifPresent(a -> agreements.put(key, new UserMeResponse.AgreementState(
                        a.isAgreed(), a.getVersion(),
                        a.getCreatedAt() != null ? a.getCreatedAt().toString() : null))));

        return new UserMeResponse(
                UserResponse.from(user, summary),
                user.getBirthDate() != null ? user.getBirthDate().toString() : null,
                user.getGender() != null ? user.getGender().name() : null,
                agreements);
    }
}
