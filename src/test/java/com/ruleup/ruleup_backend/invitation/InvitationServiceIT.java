package com.ruleup.ruleup_backend.invitation;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.me.dto.MeInvitationResponse;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.OAuthProvider;
import com.ruleup.ruleup_backend.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class InvitationServiceIT {

    @Autowired InvitationService invitationService;
    @Autowired UserRepository userRepository;

    private User newUser() {
        String u = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.saveAndFlush(User.create(
                OAuthProvider.KAKAO, "sub-" + u, null, "u-" + u, null, List.of()));
    }

    @Test
    @DisplayName("초대 코드는 유저당 1개로 멱등 생성, 링크/보상 문구 포함")
    void idempotentCode() {
        User u = newUser();
        MeInvitationResponse a = invitationService.myInvitation(u.getId());
        MeInvitationResponse b = invitationService.myInvitation(u.getId());

        assertThat(a.inviteCode()).hasSize(6);
        assertThat(a.inviteCode()).isEqualTo(b.inviteCode());   // 멱등
        assertThat(a.inviteUrl()).endsWith(a.inviteCode());
        assertThat(a.rewardDescription()).isNotBlank();
        assertThat(a.invitees()).isEmpty();
    }

    @Test
    @DisplayName("피초대 가입 기록 → 초대자 현황에 노출")
    void recordAndList() {
        User inviter = newUser();
        String code = invitationService.myInvitation(inviter.getId()).inviteCode();
        User invitee = newUser();

        invitationService.recordSignup(code, invitee.getId(), Instant.now());

        List<MeInvitationResponse.Invitee> invitees = invitationService.myInvitation(inviter.getId()).invitees();
        assertThat(invitees).hasSize(1);
        assertThat(invitees.get(0).status()).isEqualTo("SIGNED_UP");
        assertThat(invitees.get(0).nickname()).isNotNull();
    }

    @Test
    @DisplayName("유효하지 않은 코드/자기초대는 무시")
    void ignoreInvalidAndSelf() {
        User inviter = newUser();
        String code = invitationService.myInvitation(inviter.getId()).inviteCode();

        invitationService.recordSignup("ZZZZZZ", newUser().getId(), Instant.now());   // 없는 코드
        invitationService.recordSignup(code, inviter.getId(), Instant.now());          // 자기초대

        assertThat(invitationService.myInvitation(inviter.getId()).invitees()).isEmpty();
    }

    @Test
    @DisplayName("피초대 기록은 1회만(멱등)")
    void singleRecordPerInvitee() {
        User inviter = newUser();
        String code = invitationService.myInvitation(inviter.getId()).inviteCode();
        User invitee = newUser();

        invitationService.recordSignup(code, invitee.getId(), Instant.now());
        invitationService.recordSignup(code, invitee.getId(), Instant.now());

        assertThat(invitationService.myInvitation(inviter.getId()).invitees()).hasSize(1);
    }
}
