package com.ruleup.ruleup_backend.room;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.domain.*;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.room.domain.Notice;
import com.ruleup.ruleup_backend.room.dto.RoomDtos;
import com.ruleup.ruleup_backend.room.repository.NoticeRepository;
import com.ruleup.ruleup_backend.room.service.RoomService;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class RoomServiceIT {

    @Autowired RoomService roomService;
    @Autowired ChallengeRepository challengeRepository;
    @Autowired ChallengeMemberRepository memberRepository;
    @Autowired NoticeRepository noticeRepository;
    @Autowired UserRepository userRepository;

    private User newUser() {
        String u = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.saveAndFlush(User.create(
                OAuthProvider.KAKAO, "sub-" + u, null, "u-" + u, null, List.of()));
    }

    private Challenge groupChallenge(UUID ownerId) {
        Challenge c = Challenge.create(ownerId, "매일 아침 6시 기상", null, null, "WAKE_UP", ParticipationType.GROUP, null, 20,
                List.of("MON"), 14, LocalDate.now(), null, VerificationConfig.manual(null), new LinkedHashMap<>(),
                new PenaltyConfig(BigDecimal.ONE, null, false), new RewardConfig(BigDecimal.ONE), Anonymity.REAL, false);
        c.activate();
        return challengeRepository.saveAndFlush(c);
    }

    private void owner(Challenge c, UUID userId, String rate, int success, VerificationStatus today) {
        ChallengeMember m = ChallengeMember.owner(c.getId(), userId);
        m.applyProgress(success, 0, new BigDecimal(rate), today, Instant.now());
        memberRepository.saveAndFlush(m);
    }

    private User member(Challenge c, String rate, int success) {
        User u = newUser();
        ChallengeMember m = ChallengeMember.join(c.getId(), u.getId(), MemberStatus.ACTIVE);
        m.applyCounts(success, 0, new BigDecimal(rate));
        memberRepository.saveAndFlush(m);
        return u;
    }

    @Test
    @DisplayName("랭킹: progressRate desc 정렬 + myRank(gapToAbove)")
    void ranking() {
        User o = newUser();
        Challenge c = groupChallenge(o.getId());
        owner(c, o.getId(), "92.00", 11, VerificationStatus.PENDING);
        member(c, "98.00", 12);   // 1위
        member(c, "85.00", 10);   // 3위

        RoomDtos.RankingResponse res = roomService.ranking(o.getId(), c.getId());

        assertThat(res.rankings()).hasSize(3);
        assertThat(res.rankings().get(0).progressRate()).isEqualByComparingTo("98.00");
        assertThat(res.rankings().get(0).rank()).isEqualTo(1);
        assertThat(res.myRank().rank()).isEqualTo(2);                 // owner 92 → 2위
        assertThat(res.myRank().gapToAbove()).isEqualByComparingTo("6.00");   // 98-92
    }

    @Test
    @DisplayName("방 홈: 요약·고정공지·미읽음·상위3·myTodayStatus·myRole")
    void room() {
        User o = newUser();
        Challenge c = groupChallenge(o.getId());
        owner(c, o.getId(), "92.00", 11, VerificationStatus.PENDING);
        member(c, "98.00", 12);
        member(c, "85.00", 10);
        noticeRepository.saveAndFlush(Notice.create(c.getId(), o.getId(), "인증창 변경", "본문", true));

        RoomDtos.RoomResponse res = roomService.room(o.getId(), c.getId());

        assertThat(res.myRole()).isEqualTo("OWNER");
        assertThat(res.summary().title()).isEqualTo("매일 아침 6시 기상");
        assertThat(res.summary().participantCount()).isEqualTo(3);
        assertThat(res.summary().completionRate()).isEqualTo(92);      // round(avg(92,98,85))
        assertThat(res.summary().avgMannerTemperature()).isNotNull();
        assertThat(res.pinnedNotice()).isNotNull();
        assertThat(res.pinnedNotice().isRead()).isFalse();
        assertThat(res.unreadNoticeCount()).isEqualTo(1);
        assertThat(res.topRanking()).hasSize(3);
        assertThat(res.myTodayStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("비멤버는 랭킹/방 홈 403 NOT_A_MEMBER")
    void nonMemberForbidden() {
        User o = newUser();
        Challenge c = groupChallenge(o.getId());
        owner(c, o.getId(), "50.00", 5, VerificationStatus.PENDING);
        User outsider = newUser();

        assertThatThrownBy(() -> roomService.ranking(outsider.getId(), c.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_A_MEMBER);
        assertThatThrownBy(() -> roomService.room(outsider.getId(), c.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_A_MEMBER);
    }
}
