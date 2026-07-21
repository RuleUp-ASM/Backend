package com.ruleup.ruleup_backend.reputation;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeStatus;
import com.ruleup.ruleup_backend.challenge.domain.MemberStatus;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.reputation.domain.ReputationScore;
import com.ruleup.ruleup_backend.reputation.domain.ReputationSnapshot;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 매너 온도 일일 배치(온도 계산 테크스펙 V1 §4). 유저마다 하루 1스텝:
 *   ① 활성 방들의 페이스 점수 합 s_d 계산
 *   ② 볼륨 V ← EMA, 자격/연차 B 갱신
 *   ③ 온도 T = f(V+B) 재계산
 *
 * <p>멱등: {@code lastCalculatedDate < today} 가드 + 유저 행 비관적 락(EMA는 두 번 적용하면 안 됨).
 * 배치 누락 시 누락 일수만큼 하루 1스텝을 순차 적용(현재 s_d로 근사, maxCatchupDays 상한).
 * 온도는 서버 내부 로직 — 클라 입력으로 바뀌지 않는다(§7).
 */
@Service
@RequiredArgsConstructor
public class ReputationCalculationService {

    private static final Logger log = LoggerFactory.getLogger(ReputationCalculationService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final ReputationScoreRepository scoreRepository;
    private final ReputationSnapshotRepository snapshotRepository;
    private final ChallengeMemberRepository memberRepository;
    private final ChallengeRepository challengeRepository;
    private final TemperatureFormula formula;
    private final ReputationTemperatureProperties props;

    /** 매일 03:00 KST: 오늘자 미계산 유저 전원 온도 갱신. */
    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Seoul")
    public void runDaily() {
        LocalDate today = LocalDate.now(KST);
        List<UUID> targets = scoreRepository.findUserIdsToCalculate(today);
        int done = 0;
        for (UUID userId : targets) {
            try {
                if (recalculate(userId, today)) done++;
            } catch (Exception e) {
                log.warn("온도 계산 실패 userId={}: {}", userId, e.getMessage());   // 한 명 실패가 배치를 멈추지 않게
            }
        }
        log.info("매너 온도 배치 완료: 대상 {}명 중 {}명 갱신(date={})", targets.size(), done, today);
    }

    /**
     * 유저 1명 온도 재계산(배치·테스트 공용). 이미 오늘 계산됐으면 skip(false).
     * @return 갱신했으면 true.
     */
    @Transactional
    public boolean recalculate(UUID userId, LocalDate today) {
        ReputationScore score = scoreRepository.findByUserIdForUpdate(userId).orElse(null);
        if (score == null || score.isCalculatedFor(today)) return false;

        double sd = dailyIndex(userId);

        // 상태 로드(하루 1스텝을 누락일수만큼 순차 적용).
        double v = score.getVolumeIndex().doubleValue();
        double b = score.getTenureBonus().doubleValue();
        int qDays = score.getQualifyingDays();
        LocalDate lastQual = score.getLastQualifyingDate();

        LocalDate from = score.getLastCalculatedDate();
        int steps = (from == null) ? 1
                : (int) Math.min(ChronoUnit.DAYS.between(from, today), props.getMaxCatchupDays());
        LocalDate cursor = (from == null) ? today : from;

        for (int i = 0; i < steps; i++) {
            cursor = (from == null) ? today : from.plusDays(i + 1L);
            // ① 볼륨 EMA
            v = v + props.getVolumeLearningRate() * (sd - v);
            // ② 자격/연차
            if (sd >= props.getQualifyingThreshold()) {
                qDays += 1;
                lastQual = cursor;
                if (qDays > props.getTenureAccrualAfterDays()) {
                    b = Math.min(b + props.getTenureAccrualPerDay(), props.getTenureCap());
                }
            } else {
                boolean grace = lastQual != null
                        && ChronoUnit.DAYS.between(lastQual, cursor) <= props.getGraceDays();
                if (!grace) {
                    b = Math.max(b - props.getTenureDecayPerDay(), 0.0);
                }
            }
        }

        BigDecimal prevTemp = score.getMannerTemperature();
        BigDecimal temperature = formula.temperature(v + b);   // ③ T = f(V+B)
        score.applyCalculation(v, b, qDays, lastQual, temperature, today);
        score.updatePeakIfHigher(temperature, today);          // 역대 최고온도 경신
        scoreRepository.save(score);

        // 일별 온도 스냅샷 멱등 적재(온도 상세 recentChanges + 통계 mannerDelta 원천).
        if (!snapshotRepository.existsByUserIdAndSnapshotDate(userId, today)) {
            BigDecimal delta = temperature.subtract(prevTemp).setScale(2, java.math.RoundingMode.HALF_UP);
            snapshotRepository.save(ReputationSnapshot.of(userId, today, temperature, delta, snapshotLabel(sd)));
        }
        return true;
    }

    /** 스냅샷 규칙 라벨(s_d 부호 기반): 자격 유지 / 페이스 하락 / 유지. */
    private String snapshotLabel(double sd) {
        if (sd >= props.getQualifyingThreshold()) return "자격일 유지";
        if (sd < 0) return "페이스 하락";
        return "페이스 유지";
    }

    /**
     * 일별 지수 s_d = Σ(활성 방의 페이스 점수 c_i). (§1.1)
     * 활성 = ACTIVE 멤버 × ACTIVE(진행중)·미삭제 챌린지. 경과 예정 인증일이 없는 방은 기여 없음.
     * r_i = 확정 성공일 / (성공일+실패일)  — 인증 확정분을 "경과 예정 인증일"로 사용(판정 유예 내재).
     */
    private double dailyIndex(UUID userId) {
        List<ChallengeMember> members = memberRepository.findByUserIdAndStatus(userId, MemberStatus.ACTIVE);
        if (members.isEmpty()) return 0.0;

        Map<UUID, Challenge> byId = challengeRepository
                .findAllById(members.stream().map(ChallengeMember::getChallengeId).toList())
                .stream().collect(Collectors.toMap(Challenge::getId, c -> c));

        double sd = 0.0;
        for (ChallengeMember m : members) {
            Challenge c = byId.get(m.getChallengeId());
            if (c == null || c.getDeletedAt() != null || c.getStatus() != ChallengeStatus.ACTIVE) continue;
            int elapsed = m.getSuccessDays() + m.getFailDays();   // 경과(확정)한 예정 인증일
            if (elapsed <= 0) continue;                            // 아직 판정된 날 없음 → 기여 없음
            double r = (double) m.getSuccessDays() / elapsed;
            sd += formula.paceScore(r);
        }
        return sd;
    }
}
