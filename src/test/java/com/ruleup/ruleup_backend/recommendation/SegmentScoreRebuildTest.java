package com.ruleup.ruleup_backend.recommendation;

import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.recommendation.domain.SegmentType;
import com.ruleup.ruleup_backend.recommendation.domain.TemplateSegmentScore;
import com.ruleup.ruleup_backend.recommendation.repository.SegmentTypeWeightRepository;
import com.ruleup.ruleup_backend.recommendation.repository.TemplateSegmentScoreRepository;
import com.ruleup.ruleup_backend.recommendation.service.Segment;
import com.ruleup.ruleup_backend.recommendation.service.SegmentResolver;
import com.ruleup.ruleup_backend.recommendation.service.SegmentScoreReader;
import com.ruleup.ruleup_backend.recommendation.service.SegmentScoreService;
import com.ruleup.ruleup_backend.recommendation.service.SegmentTypeWeightReader;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 04:00 세그먼트 재집계 — DB 가 (생성자, 템플릿)별로 묶어 준 개수를 세그먼트 점수로 펼친다.
 * 기간·삭제·봇방장 필터는 쿼리 몫이라 {@code SegmentScoreWindowQueryIT} 가 실제 DB 로 본다.
 */
class SegmentScoreRebuildTest {

    private final ChallengeRepository challengeRepository = mock(ChallengeRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final SegmentResolver segmentResolver = mock(SegmentResolver.class);
    private final TemplateSegmentScoreRepository scoreRepository = mock(TemplateSegmentScoreRepository.class);

    private final SegmentScoreService service = new SegmentScoreService(
            challengeRepository, userRepository, segmentResolver, scoreRepository,
            mock(SegmentTypeWeightRepository.class), mock(SegmentScoreReader.class),
            mock(SegmentTypeWeightReader.class));

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "windowDays", 30);
        TransactionSynchronizationManager.initSynchronization();   // afterCommit 등록 자리
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    private static Object[] row(UUID creatorId, long templateId, long count) {
        return new Object[]{creatorId, templateId, count};
    }

    private User creator(UUID id, String country) {
        User u = mock(User.class);
        when(u.getId()).thenReturn(id);
        when(segmentResolver.resolve(u)).thenReturn(List.of(
                new Segment(SegmentType.GLOBAL, "ALL"), new Segment(SegmentType.COUNTRY, country)));
        return u;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Integer> savedCounts() {
        ArgumentCaptor<List<TemplateSegmentScore>> rows = ArgumentCaptor.forClass(List.class);
        verify(scoreRepository).saveAll(rows.capture());
        return rows.getValue().stream().collect(Collectors.toMap(
                s -> s.getSegmentType() + "/" + s.getSegmentValue() + "/" + s.getTemplateId(),
                TemplateSegmentScore::getSelectionCount));
    }

    @Test
    @DisplayName("묶음 개수 n 은 챌린지 n개를 하나씩 센 것과 같은 점수가 된다")
    void groupedCountsMatchPerChallengeCounting() {
        UUID kr = UUID.randomUUID();
        UUID us = UUID.randomUUID();
        User krUser = creator(kr, "KR");
        User usUser = creator(us, "US");
        // KR 생성자: 템플릿 7 을 2번, 8 을 1번 / US 생성자: 템플릿 7 을 1번
        when(challengeRepository.countTemplateChoicesByCreatorSince(any()))
                .thenReturn(List.of(row(kr, 7, 2), row(kr, 8, 1), row(us, 7, 1)));
        when(userRepository.findAllById(anyIterable())).thenReturn(List.of(krUser, usUser));

        service.rebuild();

        assertThat(savedCounts()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "GLOBAL/ALL/7", 3, "GLOBAL/ALL/8", 1,
                "COUNTRY/KR/7", 2, "COUNTRY/KR/8", 1,
                "COUNTRY/US/7", 1));
    }

    @Test
    @DisplayName("생성자는 한 번에 읽고, 한 명씩 findById 하지 않는다")
    @SuppressWarnings("unchecked")
    void loadsCreatorsInOneBatch() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        User ua = creator(a, "KR");
        User ub = creator(b, "KR");
        when(challengeRepository.countTemplateChoicesByCreatorSince(any()))
                .thenReturn(List.of(row(a, 7, 1), row(a, 8, 1), row(b, 7, 1)));
        when(userRepository.findAllById(anyIterable())).thenReturn(List.of(ua, ub));

        service.rebuild();

        ArgumentCaptor<Iterable<UUID>> ids = ArgumentCaptor.forClass(Iterable.class);
        verify(userRepository, times(1)).findAllById(ids.capture());
        assertThat(ids.getValue()).containsExactlyInAnyOrder(a, b);
        verify(userRepository, never()).findById(any());
        verify(segmentResolver, times(1)).resolve(ua);   // 같은 생성자는 세그먼트도 한 번만 푼다
    }

    @Test
    @DisplayName("사용자 행이 없는 생성자는 건너뛰고 나머지로 재집계한다")
    void skipsMissingCreator() {
        UUID gone = UUID.randomUUID();
        UUID alive = UUID.randomUUID();
        User aliveUser = creator(alive, "KR");
        when(challengeRepository.countTemplateChoicesByCreatorSince(any()))
                .thenReturn(List.of(row(gone, 7, 5), row(alive, 7, 1)));
        when(userRepository.findAllById(anyIterable())).thenReturn(List.of(aliveUser));

        service.rebuild();

        assertThat(savedCounts()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "GLOBAL/ALL/7", 1, "COUNTRY/KR/7", 1));
    }

    @Test
    @DisplayName("윈도우 시작은 지금부터 windowDays 일 전이다")
    void queriesWithWindowStart() {
        when(challengeRepository.countTemplateChoicesByCreatorSince(any())).thenReturn(List.of());

        service.rebuild();

        ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
        verify(challengeRepository).countTemplateChoicesByCreatorSince(since.capture());
        assertThat(Duration.between(since.getValue(), Instant.now()).toSeconds())
                .isCloseTo(Duration.ofDays(30).toSeconds(), within(5L));
        verify(userRepository, never()).findAllById(anyIterable());   // 대상이 없으면 사용자 조회도 없다
        assertThat(savedCounts()).isEmpty();
    }
}
