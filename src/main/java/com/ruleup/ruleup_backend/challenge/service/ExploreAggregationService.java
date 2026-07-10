package com.ruleup.ruleup_backend.challenge.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.MemberStatus;
import com.ruleup.ruleup_backend.challenge.domain.TemplateStats;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.challenge.repository.TemplateStatsRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 탐색 역정규화 집계 배치(탐색 스펙 §3.2·§4). 질의 시점 집계를 피하려 주기적으로 통째 재계산한다.
 *  - 템플릿: usageCount(파생 챌린지 참여자 합) + completionRate(완료 회차 완주자/참여자, 표본>10) → TemplateStats.
 *  - 방:     failCount(확정 실패 인원) → Challenge.failCount.
 * 읽기 전용·멱등이라 다중 인스턴스에서 ShedLock 불필요(각 인스턴스가 자기 계산으로 수렴).
 */
@Service
@RequiredArgsConstructor
public class ExploreAggregationService {

    private static final Logger log = LoggerFactory.getLogger(ExploreAggregationService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final ChallengeRepository challengeRepository;
    private final ChallengeMemberRepository memberRepository;
    private final TemplateStatsRepository templateStatsRepository;

    /** 1시간 주기: 템플릿 통계 + 방 failCount 재집계. */
    @Scheduled(fixedDelay = 3_600_000L)
    @Transactional
    public void rebuild() {
        rebuildTemplateStats();
        rebuildRoomFailCounts();
    }

    // ===== 템플릿 통계(usageCount · completionRate) =====
    private void rebuildTemplateStats() {
        // 1) 사용자 수: 파생된 모든(삭제 제외) 챌린지의 참여자 합.
        Map<Long, Long> usageByTemplate = new HashMap<>();
        for (Object[] row : challengeRepository.sumParticipantsByTemplate()) {
            usageByTemplate.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }

        // 2) 완주율: 완료 회차의 (참여자, 완주자)를 템플릿 단위로 합산.
        Map<UUID, Long> challengeToTemplate = new HashMap<>();
        for (Object[] row : challengeRepository.findCompletedTemplateChallenges()) {
            challengeToTemplate.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        Map<Long, long[]> completionByTemplate = new HashMap<>();   // templateId → [participants, finishers]
        if (!challengeToTemplate.isEmpty()) {
            for (Object[] row : memberRepository.aggregateCompletionByChallenge(
                    challengeToTemplate.keySet(), MemberStatus.ACTIVE)) {
                UUID challengeId = (UUID) row[0];
                long participants = ((Number) row[1]).longValue();
                long finishers = (row[2] != null) ? ((Number) row[2]).longValue() : 0L;
                Long templateId = challengeToTemplate.get(challengeId);
                if (templateId == null) continue;
                long[] acc = completionByTemplate.computeIfAbsent(templateId, k -> new long[2]);
                acc[0] += participants;
                acc[1] += finishers;
            }
        }

        // 3) 전량 재작성.
        Set<Long> templateIds = new HashSet<>(usageByTemplate.keySet());
        templateIds.addAll(completionByTemplate.keySet());
        List<TemplateStats> rows = new ArrayList<>(templateIds.size());
        for (Long templateId : templateIds) {
            long usage = usageByTemplate.getOrDefault(templateId, 0L);
            long[] comp = completionByTemplate.getOrDefault(templateId, new long[2]);
            rows.add(TemplateStats.rebuilt(templateId, usage, comp[0], comp[1]));
        }
        templateStatsRepository.deleteAllInBatch();
        templateStatsRepository.saveAll(rows);
        log.info("템플릿 통계 재집계: {}개 템플릿", rows.size());
    }

    // ===== 방 확정 실패 인원(failCount) =====
    private void rebuildRoomFailCounts() {
        Map<UUID, Integer> failByChallenge = new HashMap<>();
        for (Object[] row : memberRepository.aggregateConfirmedFailByChallenge(MemberStatus.ACTIVE)) {
            long f = (row[1] != null) ? ((Number) row[1]).longValue() : 0L;
            failByChallenge.put((UUID) row[0], (int) f);
        }

        // 후보(종료 전 공개) 챌린지에 대해 failCount 갱신(집계에 없으면 0으로 리셋).
        LocalDate today = LocalDate.now(KST);
        List<Challenge> candidates = challengeRepository.findExploreCandidates(today);
        for (Challenge c : candidates) {
            c.applyFailCount(failByChallenge.getOrDefault(c.getId(), 0));
        }
        challengeRepository.saveAll(candidates);
        log.info("방 failCount 재집계: 후보 {}개", candidates.size());
    }
}
