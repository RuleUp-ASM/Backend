package com.ruleup.ruleup_backend.watcher;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.domain.*;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.routine.domain.VerificationConfig;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.OAuthProvider;
import com.ruleup.ruleup_backend.user.domain.User;
import com.ruleup.ruleup_backend.watcher.dto.*;
import com.ruleup.ruleup_backend.watcher.infra.ContactCipher;
import com.ruleup.ruleup_backend.watcher.infra.SmsSender;
import com.ruleup.ruleup_backend.watcher.repository.WatcherRepository;
import com.ruleup.ruleup_backend.watcher.service.WatcherInvitationService;
import com.ruleup.ruleup_backend.watcher.service.WatcherService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 감시자 초대→동의→목록→수신거부 전 과정 통합 검증(실 MySQL) — §5.9 합법성 게이트.
 *  - 비유저: OTP 발송→검증→동의. 연락처 암호화 저장 + 생성자엔 마스킹만.
 *  - 수신거부 → REVOKED + 30일 재초대 차단.
 *  - 유저 수락 = 인앱 동의.
 *  - 무료 3명 한도.
 */
@Import({TestcontainersConfiguration.class, WatcherFlowIT.TestConfig.class})
@SpringBootTest
@Transactional
class WatcherFlowIT {

    /** OTP 코드를 캡처하는 SMS fake(테스트에서 코드 확인용). */
    static class CapturingSmsSender implements SmsSender {
        volatile String lastPhone;
        volatile String lastCode;
        public void sendOtp(String phone, String code) { this.lastPhone = phone; this.lastCode = code; }
        public void sendFailureNotice(String phone, String message, String unsubscribeUrl) { }
    }

    @TestConfiguration
    static class TestConfig {
        @Bean @Primary CapturingSmsSender capturingSmsSender() { return new CapturingSmsSender(); }
    }

    @Autowired WatcherInvitationService invitationService;
    @Autowired WatcherService watcherService;
    @Autowired ChallengeRepository challengeRepository;
    @Autowired UserRepository userRepository;
    @Autowired WatcherRepository watcherRepository;
    @Autowired ContactCipher contactCipher;
    @Autowired CapturingSmsSender sms;

    private static final String PHONE = "010-1234-5678";

    private UUID newUser(String nick) {
        return userRepository.saveAndFlush(User.create(
                OAuthProvider.KAKAO, "sub-" + UUID.randomUUID(), null, nick, null, List.of())).getId();
    }

    private UUID newChallenge(UUID ownerId) {
        Challenge c = Challenge.create(
                ownerId, "아침 7시 기상", null, null, "EXERCISE",
                ParticipationType.SOLO, null, List.of("MON"), 14, LocalDate.now(),
                null, VerificationConfig.manual(null), new LinkedHashMap<>(),
                new PenaltyConfig(BigDecimal.ONE, null, false), new RewardConfig(BigDecimal.ONE),
                Anonymity.REAL, false);
        return challengeRepository.saveAndFlush(c).getId();
    }

    @Test
    @DisplayName("비유저: OTP→동의 → 연락처 암호화 저장 + 생성자엔 마스킹만")
    void nonUser_consent_masks_and_encrypts() {
        UUID owner = newUser("방장");
        UUID challengeId = newChallenge(owner);

        var inv = invitationService.createInvitation(owner, challengeId);
        // 진입(비유저): viewerIsUser=false
        var entry = invitationService.getByToken(inv.token(), null);
        assertThat(entry.viewerIsUser()).isFalse();
        assertThat(entry.blocked()).isFalse();

        var otp = invitationService.sendOtp(inv.token(), PHONE);
        assertThat(sms.lastCode).isNotNull();

        var consent = invitationService.consent(inv.token(), UUID.fromString(otp.otpId()), sms.lastCode, true);
        assertThat(consent.channel()).isEqualTo("SMS");
        assertThat(consent.phoneMasked()).isEqualTo("010-****-5678");

        // 저장된 연락처는 암호화 — 평문이 아니고, 복호화하면 정규화 번호.
        var watcher = watcherRepository.findById(UUID.fromString(consent.watcherId())).orElseThrow();
        assertThat(watcher.getContactEnc()).isNotNull();
        assertThat(contactCipher.decrypt(watcher.getContactEnc())).isEqualTo("01012345678");

        // 생성자 목록엔 마스킹만(원본 번호 미노출).
        var list = watcherService.list(owner, challengeId, "ALL");
        assertThat(list.watchers()).anySatisfy(w -> {
            assertThat(w.contactMasked()).isEqualTo("010-****-5678");
            assertThat(w.contactMasked()).doesNotContain("1234");
        });
    }

    @Test
    @DisplayName("수신거부 → REVOKED + 동일 생성자 30일 재초대 차단(OTP에서 WATCHER_BLOCKED)")
    void unsubscribe_blocks_reinvite() {
        UUID owner = newUser("방장");
        UUID challengeId = newChallenge(owner);

        var inv1 = invitationService.createInvitation(owner, challengeId);
        var otp1 = invitationService.sendOtp(inv1.token(), PHONE);
        var consent1 = invitationService.consent(inv1.token(), UUID.fromString(otp1.otpId()), sms.lastCode, true);

        var watcher = watcherRepository.findById(UUID.fromString(consent1.watcherId())).orElseThrow();
        String unsubToken = watcher.getUnsubscribeToken();
        assertThat(unsubToken).isNotBlank();

        var unsub = invitationService.unsubscribe(unsubToken);
        assertThat(unsub.status()).isEqualTo("REVOKED");

        // 같은 번호로 재초대 후 OTP 시도 → 30일 차단.
        var inv2 = invitationService.createInvitation(owner, challengeId);
        assertThatThrownBy(() -> invitationService.sendOtp(inv2.token(), PHONE))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.WATCHER_BLOCKED);
    }

    @Test
    @DisplayName("유저 수락 = 인앱 동의(채널 IN_APP)")
    void user_accept_is_in_app_consent() {
        UUID owner = newUser("방장");
        UUID viewer = newUser("감시자");
        UUID challengeId = newChallenge(owner);

        var inv = invitationService.createInvitation(owner, challengeId);
        var entry = invitationService.getByToken(inv.token(), viewer);
        assertThat(entry.viewerIsUser()).isTrue();

        var accept = invitationService.accept(inv.token(), viewer);
        assertThat(accept.channel()).isEqualTo("IN_APP");
        // 챌린지 미시작(RECRUITING) → CONSENTED 대기.
        assertThat(accept.status()).isEqualTo("CONSENTED");
    }

    @Test
    @DisplayName("무료 3명 한도 초과 시 WATCHER_LIMIT_EXCEEDED")
    void limit_exceeded() {
        UUID owner = newUser("방장");
        UUID challengeId = newChallenge(owner);

        invitationService.createInvitation(owner, challengeId);
        invitationService.createInvitation(owner, challengeId);
        invitationService.createInvitation(owner, challengeId);

        assertThatThrownBy(() -> invitationService.createInvitation(owner, challengeId))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.WATCHER_LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("OTP 코드 불일치 → OTP_INVALID")
    void otp_wrong_code() {
        UUID owner = newUser("방장");
        UUID challengeId = newChallenge(owner);

        var inv = invitationService.createInvitation(owner, challengeId);
        var otp = invitationService.sendOtp(inv.token(), PHONE);

        assertThatThrownBy(() ->
                invitationService.consent(inv.token(), UUID.fromString(otp.otpId()), "000000", true))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.OTP_INVALID);
    }
}
