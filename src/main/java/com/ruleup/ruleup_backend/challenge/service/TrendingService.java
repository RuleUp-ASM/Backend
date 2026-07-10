package com.ruleup.ruleup_backend.challenge.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.dto.TrendingResponse;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 실시간 인기(탐색 §2.1). 순수 참여 속도 = 최근 24h join 이벤트의 지수감쇠 합(반감기 6h). 필터 미적용.
 *
 * <p>10분 주기 배치로 후보(종료 전) 챌린지의 스코어를 산출해 (1) 랭킹 스냅샷을 메모리에 적재하고
 * (2) 목록 화면 인기순 정렬용으로 {@code challenge.trendingScore} 컬럼도 갱신한다. 읽기 전용·멱등
 * 집계라 ECS 다중 인스턴스에서 ShedLock 불필요(각 인스턴스가 자기 스냅샷을 채운다, 최대 10분 지연 허용).
 */
@Service
@RequiredArgsConstructor
public class TrendingService {

    private static final Logger log = LoggerFactory.getLogger(TrendingService.class);

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;
    private static final int WINDOW_HOURS = 24;
    private static final double HALF_LIFE_HOURS = 6.0;                 // 반감기 6h(초기값)
    private static final double LAMBDA = Math.log(2) / HALF_LIFE_HOURS; // λ = ln2/H
    private static final int TOP_N = 20;                               // 서버는 20개, 클라는 5개 사용

    private final ChallengeRepository challengeRepository;
    private final ChallengeMemberRepository memberRepository;

    /** 최신 랭킹 스냅샷(배치가 교체, 조회는 그대로 읽음). 초기값 = 빈 목록. */
    private final AtomicReference<TrendingResponse> snapshot =
            new AtomicReference<>(new TrendingResponse(ISO.format(Instant.EPOCH), List.of()));

    /** 조회: 캐시된 인기 랭킹(최대 10분 지연). */
    public TrendingResponse getTrending() {
        return snapshot.get();
    }

    /** 10분 주기: 후보 챌린지의 인기 스코어 재계산 → 스냅샷 교체 + trendingScore 컬럼 갱신. */
    @Scheduled(fixedDelay = 600_000L)
    @Transactional
    public void rebuild() {
        Instant now = Instant.now();
        Instant since = now.minus(Duration.ofHours(WINDOW_HOURS));
        LocalDate today = LocalDate.now(KST);

        List<Challenge> candidates = challengeRepository.findExploreCandidates(today);

        // 최근 24h join 이벤트를 챌린지별로 모은다.
        Map<UUID, List<Instant>> joinsByChallenge = new HashMap<>();
        for (ChallengeMember m : memberRepository.findByJoinedAtAfter(since)) {
            joinsByChallenge.computeIfAbsent(m.getChallengeId(), k -> new ArrayList<>()).add(m.getJoinedAt());
        }

        List<Ranked> ranked = new ArrayList<>();
        for (Challenge c : candidates) {
            List<Instant> joins = joinsByChallenge.getOrDefault(c.getId(), List.of());
            double score = decayedScore(joins, now);
            c.applyTrendingScore(score);                 // 목록 인기순 정렬용 컬럼 갱신(0 포함)
            if (score > 0) ranked.add(new Ranked(c, score, joins.size()));
        }
        challengeRepository.saveAll(candidates);

        // 스코어 내림차순, 동점은 challengeId 내림차순(안정 정렬). Top 20.
        ranked.sort(Comparator.comparingDouble((Ranked r) -> r.score).reversed()
                .thenComparing(r -> r.challenge.getId(), Comparator.reverseOrder()));

        List<TrendingResponse.Item> items = new ArrayList<>();
        int rank = 1;
        for (Ranked r : ranked) {
            if (rank > TOP_N) break;
            Challenge c = r.challenge;
            items.add(new TrendingResponse.Item(
                    rank++,
                    c.getId().toString(),
                    c.getTitle(),
                    c.getCategory(),
                    c.getParticipantCount(),
                    r.recentJoins24h,
                    c.getParticipationType().name(),
                    c.getVerificationType(),
                    c.getMinMannerTemperature(),
                    c.getEndDate().toString()));
        }
        snapshot.set(new TrendingResponse(ISO.format(now), items));
        if (log.isDebugEnabled()) {
            log.debug("인기 랭킹 재계산: 후보 {}개 중 스코어>0 {}개 → Top {} 적재",
                    candidates.size(), ranked.size(), items.size());
        }
    }

    /** Σ e^(−λ·Δt), Δt = now − joinedAt (시간 단위, 음수는 0으로 보정). */
    private double decayedScore(List<Instant> joins, Instant now) {
        double sum = 0.0;
        for (Instant at : joins) {
            double dtHours = Math.max(0.0, Duration.between(at, now).toMillis() / 3_600_000.0);
            sum += Math.exp(-LAMBDA * dtHours);
        }
        return sum;
    }

    private record Ranked(Challenge challenge, double score, int recentJoins24h) {}
}
