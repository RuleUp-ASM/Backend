package com.ruleup.ruleup_backend.recommendation;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.common.DbTime;
import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.recommendation.domain.RoutineOutcome;
import com.ruleup.ruleup_backend.recommendation.repository.RoutineOutcomeRepository;
import com.ruleup.ruleup_backend.recommendation.service.RoutineOutcomeCollector;
import com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 추천 아웃컴 수집 — <b>같은 확정 시각이 한 쪽을 넘어도 끝까지 모으는가</b>가 계약이다.
 *
 * <p>구 구현은 워터마크 시각 이상에서 앞의 5,000건만 읽었다. 확정 배치는 같은 시각으로 여러 건을
 * 닫으므로, 그 시각이 5,000건을 넘으면 워터마크가 그 시각에 머문 채 매일 같은 앞부분만 다시 읽었다.
 *
 * <p>쪽 크기를 2로 줄인 인스턴스로 검증한다. 같은 시각 5건 + 그 뒤 시각 3건이면 같은 시각만으로
 * 쪽을 넘고, 쪽을 넘기는 횟수(4회 이상)도 쪽 크기를 넘는다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RoutineOutcomeCollectorIT {

    @Autowired VerificationDailyRepository dailyRepo;
    @Autowired ChallengeRepository challengeRepo;
    @Autowired RoutineOutcomeRepository outcomeRepo;
    @Autowired TransactionTemplate txTemplate;
    @Autowired JdbcTemplate jdbc;

    private RoutineOutcomeCollector smallPageCollector() {
        return new RoutineOutcomeCollector(dailyRepo, challengeRepo, outcomeRepo, txTemplate, 2, 30);
    }

    /** 스위트가 DB 를 공유한다 — 워터마크는 전역이므로 지금 워터마크보다 뒤에 자리를 잡는다. */
    private Instant anchor() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant highWater = outcomeRepo.findMaxConfirmedAt();
        Instant base = (highWater != null && highWater.isAfter(now))
                ? highWater.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1) : now;
        // 이 시각에 아웃컴 한 건을 박아 워터마크를 고정한다 — 다른 테스트의 종결행을 소급해 읽지 않게.
        jdbc.update("INSERT INTO RoutineOutcome (id,userId,challengeId,challengeMemberId,targetDate,status,confirmedAt) "
                        + "VALUES (?,?,?,?,?,'SUCCESS',?)",
                bytes(UUID.randomUUID()), bytes(UUID.randomUUID()), bytes(UUID.randomUUID()),
                bytes(UUID.randomUUID()), LocalDate.now(), DbTime.utc(base));
        return base;
    }

    private record Row(UUID id, UUID challengeId, UUID userId, LocalDate targetDate) {}

    private Row terminal(VerificationStatus status, Instant verifiedAt) {
        Row row = new Row(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), LocalDate.now().minusDays(1));
        jdbc.update("INSERT INTO VerificationDaily (id,challengeMemberId,challengeId,userId,targetDate,status,verifiedVia,verifiedAt) "
                        + "VALUES (?,?,?,?,?,?,'AUTO',?)",
                bytes(row.id()), bytes(UUID.randomUUID()), bytes(row.challengeId()), bytes(row.userId()),
                row.targetDate(), status.name(), DbTime.utc(verifiedAt));
        return row;
    }

    private Optional<RoutineOutcome> outcomeOf(Row row) {
        return outcomeRepo.findByChallengeIdAndUserIdAndTargetDate(row.challengeId(), row.userId(), row.targetDate());
    }

    @Test
    @DisplayName("같은 확정 시각이 한 쪽을 넘어도 그 시각의 나머지와 이후 시각까지 모두 모은다")
    void collectsBeyondSameInstantPage() {
        Instant base = anchor();
        Instant tie = base.plusSeconds(1);
        Instant later = base.plusSeconds(2);

        List<Row> sameInstant = new ArrayList<>();
        for (int i = 0; i < 5; i++) sameInstant.add(terminal(VerificationStatus.SUCCESS, tie));
        List<Row> afterwards = new ArrayList<>();
        for (int i = 0; i < 3; i++) afterwards.add(terminal(VerificationStatus.FAILED, later));

        smallPageCollector().collect();

        for (Row row : sameInstant) {
            assertThat(outcomeOf(row)).as("같은 시각 %s", row.id()).hasValueSatisfying(o -> {
                assertThat(o.getStatus()).isEqualTo(VerificationStatus.SUCCESS);
                assertThat(o.getConfirmedAt()).isEqualTo(tie);
            });
        }
        for (Row row : afterwards) {
            assertThat(outcomeOf(row)).as("이후 시각 %s", row.id()).hasValueSatisfying(o ->
                    assertThat(o.getStatus()).isEqualTo(VerificationStatus.FAILED));
        }
    }

    @Test
    @DisplayName("다시 돌아도 한 행씩이고, 정정된 판정은 같은 행을 고친다")
    void rerunAndCorrectionStayIdempotent() {
        Instant base = anchor();
        Instant tie = base.plusSeconds(1);

        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) rows.add(terminal(VerificationStatus.SUCCESS, tie));
        RoutineOutcomeCollector collector = smallPageCollector();
        collector.collect();

        // 이의 등으로 판정이 정정되면 확정 시각도 새로 찍힌다.
        Row corrected = rows.get(2);
        Instant correctedAt = base.plusSeconds(5);
        jdbc.update("UPDATE VerificationDaily SET status='FAILED', verifiedVia='APPEAL', verifiedAt=? WHERE id=?",
                DbTime.utc(correctedAt), bytes(corrected.id()));
        collector.collect();
        collector.collect();

        for (Row row : rows) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM RoutineOutcome WHERE challengeId=? AND userId=? AND targetDate=?",
                    Integer.class, bytes(row.challengeId()), bytes(row.userId()), row.targetDate());
            assertThat(count).as("한 멤버×날짜에 한 행 %s", row.id()).isEqualTo(1);
        }
        assertThat(outcomeOf(corrected)).hasValueSatisfying(o -> {
            assertThat(o.getStatus()).isEqualTo(VerificationStatus.FAILED);
            assertThat(o.getConfirmedAt()).isEqualTo(correctedAt);
        });
    }

    private static byte[] bytes(UUID u) {
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(u.getMostSignificantBits());
        bb.putLong(u.getLeastSignificantBits());
        return bb.array();
    }
}
