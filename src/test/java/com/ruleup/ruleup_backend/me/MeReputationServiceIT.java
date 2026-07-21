package com.ruleup.ruleup_backend.me;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.me.dto.MeReputationResponse;
import com.ruleup.ruleup_backend.me.service.MeReputationService;
import com.ruleup.ruleup_backend.reputation.ReputationScoreRepository;
import com.ruleup.ruleup_backend.reputation.ReputationSnapshotRepository;
import com.ruleup.ruleup_backend.reputation.domain.ReputationScore;
import com.ruleup.ruleup_backend.reputation.domain.ReputationSnapshot;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class MeReputationServiceIT {

    @Autowired MeReputationService reputationService;
    @Autowired ReputationScoreRepository scoreRepository;
    @Autowired ReputationSnapshotRepository snapshotRepository;
    @Autowired UserRepository userRepository;

    private User newUser() {
        String u = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.saveAndFlush(User.create(
                OAuthProvider.KAKAO, "sub-" + u, null, "u-" + u, null, List.of()));
    }

    @Test
    @DisplayName("온도 상세: 현재/밴드라벨/다음목표/최근변동")
    void reputation() {
        User u = newUser();
        ReputationScore score = ReputationScore.createDefault(u);
        score.applyCalculation(0, 0, 0, null, new BigDecimal("78.40"), LocalDate.now());
        scoreRepository.saveAndFlush(score);
        snapshotRepository.saveAndFlush(ReputationSnapshot.of(u.getId(), LocalDate.now(),
                new BigDecimal("78.40"), new BigDecimal("0.06"), "자격일 유지"));
        snapshotRepository.saveAndFlush(ReputationSnapshot.of(u.getId(), LocalDate.now().minusDays(1),
                new BigDecimal("78.34"), new BigDecimal("0.05"), "자격일 유지"));

        MeReputationResponse res = reputationService.reputation(u.getId());

        assertThat(res.current()).isEqualByComparingTo("78.40");
        assertThat(res.bandLabel()).isEqualTo("4개를 1년 이상 or 2개를 3년 이상");
        assertThat(res.nextTier().target()).isEqualByComparingTo("80.00");
        assertThat(res.nextTier().progressRate()).isEqualByComparingTo("0.68");
        assertThat(res.recentChanges()).hasSize(2);
        assertThat(res.recentChanges().get(0).date()).isEqualTo(LocalDate.now().toString());  // 최신순
    }

    @Test
    @DisplayName("점수 없으면 초기 온도 36.5")
    void noScore() {
        User u = newUser();
        MeReputationResponse res = reputationService.reputation(u.getId());
        assertThat(res.current()).isEqualByComparingTo("36.5");
        assertThat(res.recentChanges()).isEmpty();
    }
}
