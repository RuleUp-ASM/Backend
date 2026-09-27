package com.ruleup.ruleup_backend.recommendation;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
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

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 04:00 세그먼트 재집계 — 봇방장 방 하나가 배치 전체를 깨지 않아야 한다. */
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

    private static Challenge challenge(UUID creatorId) {
        Challenge c = mock(Challenge.class);
        when(c.getTemplateId()).thenReturn(7L);
        when(c.getCreatedAt()).thenReturn(Instant.now());
        when(c.getCreatorId()).thenReturn(creatorId);
        return c;
    }

    @Test
    @DisplayName("봇방장 방(creatorId=null)은 건너뛰고 나머지로 재집계한다")
    @SuppressWarnings("unchecked")
    void skipsBotOwnedRoom() {
        UUID creatorId = UUID.randomUUID();
        User creator = mock(User.class);
        List<Challenge> challenges = List.of(challenge(null), challenge(creatorId));
        when(challengeRepository.findAll()).thenReturn(challenges);
        when(userRepository.findById(creatorId)).thenReturn(Optional.of(creator));
        when(segmentResolver.resolve(creator)).thenReturn(List.of(new Segment(SegmentType.GLOBAL, "ALL")));

        service.rebuild();

        verify(userRepository, never()).findById(isNull());
        ArgumentCaptor<List<TemplateSegmentScore>> rows = ArgumentCaptor.forClass(List.class);
        verify(scoreRepository).saveAll(rows.capture());
        assertThat(rows.getValue()).hasSize(1);
    }
}
