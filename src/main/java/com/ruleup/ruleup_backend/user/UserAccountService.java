package com.ruleup.ruleup_backend.user;

import com.ruleup.ruleup_backend.agreement.UserAgreementStateRepository;
import com.ruleup.ruleup_backend.agreement.domain.AgreementType;
import com.ruleup.ruleup_backend.agreement.domain.UserAgreementState;
import com.ruleup.ruleup_backend.auth.RefreshTokenRepository;
import com.ruleup.ruleup_backend.challenge.service.ChallengeMemberService;
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
 * RT 전부 revoke, <b>참여 중인 모든 방에서 탈퇴</b>(방장이면 봇방장 전환).
 * 개인정보 S3 아카이브·1년 파기 배치는 후속 이슈.
 */
@Service
@RequiredArgsConstructor
public class UserAccountService {

    /** 탈퇴 확인 문구 — 서버 검증 문자열로 계약의 일부. */
    static final String CONFIRM_PHRASE = "탈퇴할게요";
    /** 개인정보 아카이브 보존 기간(탈퇴 후 복원 가능 기간). */
    private static final long ARCHIVE_RETENTION_DAYS = 365;
    private static final String RESTORE_NOTE = "1년 안에 같은 소셜 계정으로 로그인하면 기록이 복원돼요";

    /**
     * agreements 응답 키(계약) ↔ 저장 enum 매핑. 약관 5종 + 법정 개별 동의 2종.
     * 구 nightPush 는 야간 동의 약관 폐지(2026-08-28)로 사라졌다.
     */
    private static final Map<AgreementType, String> AGREEMENT_KEYS = new LinkedHashMap<>() {{
        put(AgreementType.TOS, "termsOfService");
        put(AgreementType.PRIVACY, "privacyPolicy");
        put(AgreementType.LOCATION, "locationService");
        put(AgreementType.MARKETING, "marketing");
        put(AgreementType.EVENT, "event");
        put(AgreementType.LOCATION_INFO, "locationInfo");
        put(AgreementType.HEALTH_INFO, "healthInfo");
    }};

    private final UserRepository userRepository;
    private final ChallengeMemberService challengeMemberService;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserAgreementStateRepository userAgreementStateRepository;
    private final UserScoreSummaryRepository scoreSummaryRepository;

    /**
     * 회원 탈퇴 — 멱등(이미 탈퇴 상태면 무해하게 같은 응답).
     *
     * <p>정지(BANNED) 계정도 탈퇴할 수 있다. 예전에는 제재 세탁을 막으려고 403 으로 거절했지만,
     * 그러면 정지된 사람은 계정을 지울 수조차 없었다. 이제는 <b>막는 대신 따라오게</b> 한다 —
     * 탈퇴 직전 상태와 설치 ID 가 계정 행에 남아, 같은 기기에서 재가입하면 그 상태를 승계한다
     * (회원 정책 §6, {@code User#withdraw()}).
     */
    @Transactional
    public WithdrawResponse withdraw(UUID userId, String confirmPhrase) {
        if (!CONFIRM_PHRASE.equals(confirmPhrase))
            throw new BusinessException(ErrorCode.CONFIRM_PHRASE_MISMATCH);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.LOGIN_REQUIRED));

        if (!user.isWithdrawn()) {
            user.withdraw();                                                    // WITHDRAWN + deleted_at + 직전 상태 보존
            refreshTokenRepository.revokeAllByUserId(userId, Instant.now());    // 전 세션 종료
            // 참여 중인 방에서도 전부 나간다. 남겨두면 인증하지 않는 유령 멤버가 남의 방 정원을 먹고,
            // 그 방은 유령방 자동 삭제 대상에서도 빠져 영영 남는다.
            challengeMemberService.leaveAllForWithdrawal(userId);
        }
        Instant archiveExpiresAt = user.getDeletedAt().plus(ARCHIVE_RETENTION_DAYS, ChronoUnit.DAYS);
        return new WithdrawResponse(true, archiveExpiresAt.toString(), RESTORE_NOTE);
    }

    /** 내 프로필 조회 — user 블록(로그인 응답과 동일) + 생일·성별·동의 7종 현재 상태. */
    @Transactional(readOnly = true)
    public UserMeResponse me(UUID userId) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.LOGIN_REQUIRED));
        UserScoreSummary summary = scoreSummaryRepository.findById(userId).orElse(null);

        // 동의 현재 상태 — 상태 테이블 하나만 읽는다. 유저당 최대 7행이라 이력을 뒤질 이유가 없다.
        Map<AgreementType, UserAgreementState> states = new LinkedHashMap<>();
        userAgreementStateRepository.findByUserId(userId)
                .forEach(s -> states.put(s.getAgreementType(), s));
        Map<String, UserMeResponse.AgreementState> agreements = new LinkedHashMap<>();
        AGREEMENT_KEYS.forEach((type, key) -> {
            UserAgreementState s = states.get(type);
            // 기록이 없는 항목은 키 자체를 빼서 "한 번도 동의한 적 없음"을 드러낸다.
            if (s != null) agreements.put(key, new UserMeResponse.AgreementState(
                    s.isAgreed(), s.getVersion(),
                    s.getAgreedAt() != null ? s.getAgreedAt().toString() : null));
        });

        return new UserMeResponse(
                UserResponse.from(user, summary),
                user.getBirthDate() != null ? user.getBirthDate().toString() : null,
                user.getGender() != null ? user.getGender().name() : null,
                agreements);
    }
}
