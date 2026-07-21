package com.ruleup.ruleup_backend.room;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.domain.*;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.notification.NotificationRepository;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import com.ruleup.ruleup_backend.room.dto.NoticeDtos;
import com.ruleup.ruleup_backend.room.service.NoticeService;
import com.ruleup.ruleup_backend.routine.domain.VerificationConfig;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.OAuthProvider;
import com.ruleup.ruleup_backend.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class NoticeServiceIT {

    @Autowired NoticeService noticeService;
    @Autowired ChallengeRepository challengeRepository;
    @Autowired ChallengeMemberRepository memberRepository;
    @Autowired UserRepository userRepository;
    @Autowired NotificationRepository notificationRepository;

    private User newUser() {
        String u = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.saveAndFlush(User.create(
                OAuthProvider.KAKAO, "sub-" + u, null, "u-" + u, null, List.of()));
    }

    private Challenge groupChallenge(UUID ownerId) {
        Challenge c = Challenge.create(ownerId, "챌린지", null, null, "EXERCISE", ParticipationType.GROUP, null, 10,
                List.of("MON"), 14, LocalDate.now(), null, VerificationConfig.manual(null), new LinkedHashMap<>(),
                new PenaltyConfig(BigDecimal.ONE, null, false), new RewardConfig(BigDecimal.ONE), Anonymity.REAL, false);
        c.activate();
        challengeRepository.saveAndFlush(c);
        memberRepository.saveAndFlush(ChallengeMember.owner(c.getId(), ownerId));
        return c;
    }

    private User join(Challenge c) {
        User u = newUser();
        memberRepository.saveAndFlush(ChallengeMember.join(c.getId(), u.getId(), MemberStatus.ACTIVE));
        return u;
    }

    private NoticeDtos.CreateRequest create(String title, String content, boolean pinned) {
        return new NoticeDtos.CreateRequest(title, content, pinned);
    }

    @Test
    @DisplayName("작성(방장) → fan-out(멤버 인앱 알림), 목록 고정 우선·isRead")
    void createAndList() {
        User owner = newUser();
        Challenge c = groupChallenge(owner.getId());
        User m1 = join(c);
        User m2 = join(c);

        noticeService.create(owner.getId(), c.getId(), create("일반", "본문", false));
        noticeService.create(owner.getId(), c.getId(), create("고정공지", "본문2", true));

        // fan-out: 멤버 2명에게 알림(작성자 제외) — 공지 2건 = 4알림
        long m1Notices = notificationRepository.findByUserIdOrderByCreatedAtDesc(m1.getId()).stream()
                .filter(n -> n.getType() == NotificationType.NOTICE_CREATED).count();
        assertThat(m1Notices).isEqualTo(2);
        assertThat(notificationRepository.findByUserIdOrderByCreatedAtDesc(owner.getId())).isEmpty();  // 작성자 제외

        NoticeDtos.ListResponse list = noticeService.list(m1.getId(), c.getId());
        assertThat(list.notices()).hasSize(2);
        assertThat(list.notices().get(0).pinned()).isTrue();      // 고정 우선
        assertThat(list.notices().get(0).isRead()).isFalse();
    }

    @Test
    @DisplayName("상세 조회 = 읽음(멱등) → 목록 isRead true")
    void detailMarksRead() {
        User owner = newUser();
        Challenge c = groupChallenge(owner.getId());
        User m = join(c);
        var created = noticeService.create(owner.getId(), c.getId(), create("제목", "본문내용", false));
        UUID noticeId = UUID.fromString(created.noticeId());

        var d = noticeService.detail(m.getId(), c.getId(), noticeId);
        assertThat(d.content()).isEqualTo("본문내용");
        noticeService.detail(m.getId(), c.getId(), noticeId);   // 중복 진입 멱등

        assertThat(noticeService.list(m.getId(), c.getId()).notices().get(0).isRead()).isTrue();
    }

    @Test
    @DisplayName("단일 pin: 새로 고정하면 기존 고정 자동 해제(unpinnedNoticeId)")
    void singlePin() {
        User owner = newUser();
        Challenge c = groupChallenge(owner.getId());
        var a = noticeService.create(owner.getId(), c.getId(), create("A", "aaa", true));
        var b = noticeService.create(owner.getId(), c.getId(), create("B", "bbb", false));

        var res = noticeService.pin(owner.getId(), c.getId(), UUID.fromString(b.noticeId()),
                new NoticeDtos.PinRequest(true));
        assertThat(res.pinned()).isTrue();
        assertThat(res.unpinnedNoticeId()).isEqualTo(a.noticeId());   // 기존 고정 교체
    }

    @Test
    @DisplayName("수정 resetRead=true → 읽음 초기화")
    void editResetRead() {
        User owner = newUser();
        Challenge c = groupChallenge(owner.getId());
        User m = join(c);
        var created = noticeService.create(owner.getId(), c.getId(), create("제목", "본문", false));
        UUID noticeId = UUID.fromString(created.noticeId());
        noticeService.detail(m.getId(), c.getId(), noticeId);   // 읽음

        var res = noticeService.edit(owner.getId(), c.getId(), noticeId,
                new NoticeDtos.EditRequest("수정제목", "수정본문", true));
        assertThat(res.readReset()).isTrue();
        assertThat(noticeService.list(m.getId(), c.getId()).notices().get(0).isRead()).isFalse();   // 초기화됨
    }

    @Test
    @DisplayName("삭제(소프트) → 목록에서 제외")
    void softDelete() {
        User owner = newUser();
        Challenge c = groupChallenge(owner.getId());
        var created = noticeService.create(owner.getId(), c.getId(), create("제목", "본문", false));
        noticeService.delete(owner.getId(), c.getId(), UUID.fromString(created.noticeId()));
        assertThat(noticeService.list(owner.getId(), c.getId()).notices()).isEmpty();
    }

    @Test
    @DisplayName("방장 아니면 작성 NOT_CHALLENGE_OWNER, 비멤버 조회 NOT_A_MEMBER")
    void authority() {
        User owner = newUser();
        Challenge c = groupChallenge(owner.getId());
        User member = join(c);
        User outsider = newUser();

        assertThatThrownBy(() -> noticeService.create(member.getId(), c.getId(), create("x", "y", false)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_CHALLENGE_OWNER);
        assertThatThrownBy(() -> noticeService.list(outsider.getId(), c.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_A_MEMBER);
    }

    @Test
    @DisplayName("제목/본문 유효성 위반 INVALID_NOTICE_PAYLOAD")
    void invalidPayload() {
        User owner = newUser();
        Challenge c = groupChallenge(owner.getId());
        assertThatThrownBy(() -> noticeService.create(owner.getId(), c.getId(), create("", "본문", false)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_NOTICE_PAYLOAD);
    }
}
