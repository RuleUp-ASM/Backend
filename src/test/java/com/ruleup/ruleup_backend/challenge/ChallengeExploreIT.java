package com.ruleup.ruleup_backend.challenge;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.domain.Anonymity;
import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.domain.MemberStatus;
import com.ruleup.ruleup_backend.challenge.domain.ParticipationType;
import com.ruleup.ruleup_backend.challenge.domain.PenaltyConfig;
import com.ruleup.ruleup_backend.challenge.domain.RewardConfig;
import com.ruleup.ruleup_backend.challenge.domain.TemplateStats;
import com.ruleup.ruleup_backend.challenge.dto.CategoryGridResponse;
import com.ruleup.ruleup_backend.challenge.dto.ExploreResponse;
import com.ruleup.ruleup_backend.challenge.dto.TrendingResponse;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.challenge.repository.TemplateStatsRepository;
import com.ruleup.ruleup_backend.challenge.service.ChallengeCategoryService;
import com.ruleup.ruleup_backend.challenge.service.ChallengeExploreService;
import com.ruleup.ruleup_backend.challenge.service.TrendingService;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.routine.domain.SelectedMethod;
import com.ruleup.ruleup_backend.routine.domain.VerificationConfig;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.OAuthProvider;
import com.ruleup.ruleup_backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 챌린지 탐색(search 스펙) 통합 검증 — 실제 MySQL(Testcontainers).
 *  - 둘러보기: 공통 제외 → 필터(AND) → 정렬 → 커서 페이지네이션, 파라미터 검증, 표시 조건(성공/실패·완주율).
 *  - 카테고리 그리드: 진행 중 수 집계 + categoryId 매핑.
 *  - 실시간 인기: 최근 24h 참여 기반 랭킹.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class ChallengeExploreIT {

    @Autowired ChallengeExploreService exploreService;
    @Autowired ChallengeCategoryService categoryService;
    @Autowired TrendingService trendingService;
    @Autowired ChallengeRepository challengeRepository;
    @Autowired ChallengeMemberRepository memberRepository;
    @Autowired TemplateStatsRepository templateStatsRepository;
    @Autowired UserRepository userRepository;
    @Autowired CacheManager cacheManager;

    private UUID viewerId;

    @BeforeEach
    void setUp() {
        // 카테고리 그리드 캐시는 트랜잭션 밖이라 테스트 간 오염을 막으려 매번 비운다.
        var cache = cacheManager.getCache("challengeCategories");
        if (cache != null) cache.clear();
        viewerId = newUser().getId();
    }

    // ===== 둘러보기: 공통 제외 + 필터 =====

    @Test
    @DisplayName("둘러보기는 종료·미승인·다른 카테고리·못 들어가는 챌린지를 제외한다")
    void exploreExcludesAndFilters() {
        UUID owner = newUser().getId();
        Challenge visible = save(challenge(owner, "EXERCISE", ParticipationType.GROUP, null, manual(), today(), 14, 3));
        Challenge otherCat = save(challenge(owner, "READING", ParticipationType.GROUP, null, manual(), today(), 14, 3));
        Challenge ended = save(challenge(owner, "EXERCISE", ParticipationType.GROUP, null, manual(), today().minusDays(20), 14, 3));
        Challenge tooHighTemp = save(challenge(owner, "EXERCISE", ParticipationType.GROUP, new BigDecimal("40.0"), manual(), today(), 14, 3));
        Challenge deleted = challenge(owner, "EXERCISE", ParticipationType.GROUP, null, manual(), today(), 14, 3);
        deleted.softDelete();
        save(deleted);

        ExploreResponse res = exploreService.explore(
                viewerId, "EXERCISE", null, null, true, "PARTICIPANTS", null, 10);

        List<String> ids = res.challenges().stream().map(ExploreResponse.Item::challengeId).toList();
        assertThat(ids).contains(visible.getId().toString());
        assertThat(ids).doesNotContain(
                otherCat.getId().toString(),   // 다른 카테고리
                ended.getId().toString(),      // 종료(endDate<today)
                tooHighTemp.getId().toString(),// 매너 온도 하한 40 > 내 36.5 → joinable=false
                deleted.getId().toString());   // 소프트 삭제
        assertThat(res.totalCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("joinableOnly=false 면 매너 온도 하한이 높아도 노출되고 joinable=false 로 표시된다")
    void exploreJoinableFlagWhenNotFiltering() {
        UUID owner = newUser().getId();
        Challenge high = save(challenge(owner, "EXERCISE", ParticipationType.GROUP, new BigDecimal("40.0"), manual(), today(), 14, 3));

        ExploreResponse res = exploreService.explore(
                viewerId, "EXERCISE", null, null, false, "PARTICIPANTS", null, 10);

        ExploreResponse.Item item = res.challenges().stream()
                .filter(i -> i.challengeId().equals(high.getId().toString())).findFirst().orElseThrow();
        assertThat(item.joinable()).isFalse();
    }

    @Test
    @DisplayName("verificationType 필터는 AUTO/MANUAL 을 정확히 가른다")
    void exploreVerificationTypeFilter() {
        UUID owner = newUser().getId();
        Challenge manualCh = save(challenge(owner, "EXERCISE", ParticipationType.GROUP, null, manual(), today(), 14, 1));
        Challenge autoCh = save(challenge(owner, "EXERCISE", ParticipationType.GROUP, null, auto(), today(), 14, 1));

        ExploreResponse manualOnly = exploreService.explore(viewerId, "EXERCISE", null, "MANUAL", true, "PARTICIPANTS", null, 10);
        assertThat(manualOnly.challenges()).extracting(ExploreResponse.Item::challengeId)
                .contains(manualCh.getId().toString()).doesNotContain(autoCh.getId().toString());

        ExploreResponse autoOnly = exploreService.explore(viewerId, "EXERCISE", null, "AUTO", true, "PARTICIPANTS", null, 10);
        assertThat(autoOnly.challenges()).extracting(ExploreResponse.Item::challengeId)
                .contains(autoCh.getId().toString()).doesNotContain(manualCh.getId().toString());
    }

    // ===== 정렬 + 커서 페이지네이션 =====

    @Test
    @DisplayName("참여자 수 내림차순 정렬 + 커서로 페이지를 이어받아 중복/누락 없이 전량을 낸다")
    void exploreSortAndCursorPagination() {
        UUID owner = newUser().getId();
        Challenge c1 = save(challenge(owner, "EXERCISE", ParticipationType.GROUP, null, manual(), today(), 14, 5));
        Challenge c2 = save(challenge(owner, "EXERCISE", ParticipationType.GROUP, null, manual(), today(), 14, 3));
        Challenge c3 = save(challenge(owner, "EXERCISE", ParticipationType.GROUP, null, manual(), today(), 14, 1));

        ExploreResponse page1 = exploreService.explore(viewerId, "EXERCISE", null, null, true, "PARTICIPANTS", null, 2);
        assertThat(page1.totalCount()).isEqualTo(3);
        assertThat(page1.challenges()).extracting(ExploreResponse.Item::challengeId)
                .containsExactly(c1.getId().toString(), c2.getId().toString());   // 5, 3
        assertThat(page1.hasNext()).isTrue();
        assertThat(page1.nextCursor()).isNotNull();

        ExploreResponse page2 = exploreService.explore(viewerId, "EXERCISE", null, null, true, "PARTICIPANTS", page1.nextCursor(), 2);
        assertThat(page2.challenges()).extracting(ExploreResponse.Item::challengeId)
                .containsExactly(c3.getId().toString());   // 1
        assertThat(page2.hasNext()).isFalse();
        assertThat(page2.nextCursor()).isNull();
    }

    // ===== 파라미터 검증 =====

    @Test
    @DisplayName("정의되지 않은 정렬/필터/커서는 각각의 에러코드로 거부된다")
    void exploreRejectsInvalidParams() {
        assertThatThrownBy(() -> exploreService.explore(viewerId, null, null, null, true, "NONSENSE", null, 5))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_SORT_TYPE);

        assertThatThrownBy(() -> exploreService.explore(viewerId, "NOT_A_CATEGORY", null, null, true, "TRENDING", null, 5))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_FILTER_VALUE);

        assertThatThrownBy(() -> exploreService.explore(viewerId, null, "NEITHER", null, true, "TRENDING", null, 5))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_FILTER_VALUE);

        assertThatThrownBy(() -> exploreService.explore(viewerId, null, null, null, true, "TRENDING", "@@corrupt@@", 5))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.CURSOR_INVALID);
    }

    // ===== 표시 조건: 성공/실패 비율(방) + 완주율(템플릿) =====

    @Test
    @DisplayName("성공/실패 비율은 참여자>10 AND 진행률≥30% 일 때만 값이 나온다")
    void successFailRatioGating() {
        UUID owner = newUser().getId();
        // 참여자 12, 시작 10일 전·기간 20일 → 진행률 50%, 확정 실패 2명.
        Challenge qualifies = challenge(owner, "EXERCISE", ParticipationType.GROUP, null, manual(), today().minusDays(10), 20, 12);
        qualifies.applyFailCount(2);
        save(qualifies);
        // 참여자 5 → 미달.
        Challenge tooFew = save(challenge(owner, "EXERCISE", ParticipationType.GROUP, null, manual(), today().minusDays(10), 20, 5));

        ExploreResponse res = exploreService.explore(viewerId, "EXERCISE", null, null, true, "PARTICIPANTS", null, 10);

        ExploreResponse.Item qItem = item(res, qualifies);
        assertThat(qItem.successFailRatio()).isNotNull();
        assertThat(qItem.successFailRatio().successCount()).isEqualTo(10);
        assertThat(qItem.successFailRatio().failCount()).isEqualTo(2);
        assertThat(qItem.successFailRatio().successRate()).isEqualTo(0.833);

        assertThat(item(res, tooFew).successFailRatio()).isNull();
    }

    @Test
    @DisplayName("완주율은 템플릿 표본이 10 초과일 때만 값이 나오고 TemplateStats 값을 반영한다")
    void completionRateFromTemplateStats() {
        UUID owner = newUser().getId();
        long templateId = 9001L;
        templateStatsRepository.saveAndFlush(TemplateStats.rebuilt(templateId, 42, 20, 12));   // 표본 20>10 → 0.6

        Challenge c = challenge(owner, "EXERCISE", ParticipationType.GROUP, null, manual(), today(), 14, 3);
        setTemplateId(c, templateId);
        save(c);

        ExploreResponse res = exploreService.explore(viewerId, "EXERCISE", null, null, true, "PARTICIPANTS", null, 10);
        ExploreResponse.Item item = item(res, c);
        assertThat(item.completionRate()).isEqualTo(0.6);
        assertThat(item.templateUsageCount()).isEqualTo(42);
    }

    // ===== 카테고리 그리드 =====

    @Test
    @DisplayName("카테고리 그리드는 진행 중 수를 집계하고 15종 전체를 categoryId 순으로 낸다")
    void categoryGrid() {
        UUID owner = newUser().getId();
        save(challenge(owner, "EXERCISE", ParticipationType.GROUP, null, manual(), today(), 14, 1));
        save(challenge(owner, "EXERCISE", ParticipationType.GROUP, null, manual(), today(), 14, 1));
        save(challenge(owner, "READING", ParticipationType.GROUP, null, manual(), today(), 14, 1));
        // 종료된 EXERCISE 는 카운트 제외.
        save(challenge(owner, "EXERCISE", ParticipationType.GROUP, null, manual(), today().minusDays(30), 14, 1));

        CategoryGridResponse grid = categoryService.getCategories();

        assertThat(grid.items()).hasSize(15);
        CategoryGridResponse.Item exercise = grid.items().stream()
                .filter(i -> i.name().equals("운동")).findFirst().orElseThrow();
        assertThat(exercise.categoryId()).isEqualTo(1L);
        assertThat(exercise.activeChallengeCount()).isEqualTo(2);
        CategoryGridResponse.Item reading = grid.items().stream()
                .filter(i -> i.name().equals("독서")).findFirst().orElseThrow();
        assertThat(reading.activeChallengeCount()).isEqualTo(1);
    }

    // ===== 실시간 인기 =====

    @Test
    @DisplayName("실시간 인기는 최근 24h 참여가 많은 순으로 랭크되고, 참여 0 챌린지는 빠진다")
    void trendingRanksByRecentJoins() {
        UUID owner = newUser().getId();
        Challenge hot = save(challenge(owner, "EXERCISE", ParticipationType.GROUP, null, manual(), today(), 14, 3));
        Challenge warm = save(challenge(owner, "EXERCISE", ParticipationType.GROUP, null, manual(), today(), 14, 1));
        Challenge cold = save(challenge(owner, "EXERCISE", ParticipationType.GROUP, null, manual(), today(), 14, 0));

        seedJoins(hot.getId(), 3);
        seedJoins(warm.getId(), 1);
        // cold: 참여 이벤트 없음.

        trendingService.rebuild();
        TrendingResponse res = trendingService.getTrending();

        List<String> ranked = res.items().stream().map(TrendingResponse.Item::challengeId).toList();
        assertThat(ranked).containsSubsequence(hot.getId().toString(), warm.getId().toString());
        assertThat(ranked).doesNotContain(cold.getId().toString());
        assertThat(res.items().get(0).rank()).isEqualTo(1);
    }

    // ===== 헬퍼 =====

    private User newUser() {
        String uniq = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.saveAndFlush(User.create(
                OAuthProvider.KAKAO, "sub-" + uniq, null, "탐색러" + uniq, null, List.of()));
    }

    private LocalDate today() { return LocalDate.now(); }

    private VerificationConfig manual() { return VerificationConfig.manual(null); }   // MANUAL

    private VerificationConfig auto() {
        return new VerificationConfig(SelectedMethod.AUTO, null, null, null, List.of(), null);
    }

    private Challenge challenge(UUID owner, String category, ParticipationType type,
                               BigDecimal minManner, VerificationConfig vc,
                               LocalDate startDate, int durationDays, int participantCount) {
        Challenge c = Challenge.create(
                owner, "탐색 챌린지", null, null,
                category, type, minManner, List.of("MON"),
                durationDays, startDate,
                null, vc, new LinkedHashMap<>(),
                new PenaltyConfig(BigDecimal.ONE, null, false), new RewardConfig(BigDecimal.ONE),
                Anonymity.REAL, false);
        for (int i = 0; i < participantCount; i++) c.increaseParticipantCount();
        return c;
    }

    private Challenge save(Challenge c) { return challengeRepository.saveAndFlush(c); }

    /** templateId 는 create 시그니처로도 넣을 수 있지만, 명시적으로 세팅해 완주율 조인을 검증. */
    private void setTemplateId(Challenge c, long templateId) {
        try {
            var f = Challenge.class.getDeclaredField("templateId");
            f.setAccessible(true);
            f.set(c, templateId);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private void seedJoins(UUID challengeId, int count) {
        for (int i = 0; i < count; i++) {
            UUID uid = newUser().getId();
            memberRepository.saveAndFlush(ChallengeMember.join(challengeId, uid, MemberStatus.ACTIVE));
        }
    }

    private ExploreResponse.Item item(ExploreResponse res, Challenge c) {
        return res.challenges().stream()
                .filter(i -> i.challengeId().equals(c.getId().toString())).findFirst().orElseThrow();
    }
}
