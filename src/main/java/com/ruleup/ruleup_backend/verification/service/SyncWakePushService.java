package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.common.DbTime;
import com.ruleup.ruleup_backend.push.SilentPush;
import com.ruleup.ruleup_backend.push.service.PushSender;
import com.ruleup.ruleup_backend.verification.domain.VerificationConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.sql.Date;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * sync 가 끊긴 사용자의 앱을 무음 푸시로 깨운다 (인증 구현 흐름 「연결/전원 off — 클라 미기동」 단계).
 *
 * <p>정상 경로는 앱이 스스로 주기(최대 1시간)마다 sync 를 보내는 것이다. Doze·배터리 최적화·앱 강제 종료로
 * 앱이 깨어나지 못하면 신호는 기기 버퍼에만 쌓이고, 그날 판정은 귀속일 이틀 뒤 「신호 없음」 실패로 확정된다.
 * 서버가 data-only 메시지로 깨우면 앱이 expedited WorkManager 로 버퍼를 한 번에 올린다.
 *
 * <p><b>하루 두 번(15:00·20:00 KST)만</b> 돈다. 무음 푸시도 배터리를 쓰고 OS 가 남발을 제한하므로
 * 상시 감시 대신 「오후 중간 점검」과 「자기 전 마지막 기회」 두 지점만 잡았다.
 *
 * <p>대상: 오늘이 인증일인 자동 인증 방의 READY 멤버 중, 오늘 판정이 아직 끝나지 않았고
 * 그 사용자의 마지막 sync 가 {@link #STALE_AFTER} 넘게 지난 사람. sync 는 사용자 단위라 방이 여럿이어도 한 번만 보낸다.
 */
@Service
public class SyncWakePushService {

    private static final Logger log = LoggerFactory.getLogger(SyncWakePushService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 서버가 내려주는 sync 주기의 최댓값(1시간)을 넉넉히 넘긴 공백 — 이보다 짧으면 정상 주기와 구분이 안 된다. */
    static final Duration STALE_AFTER = Duration.ofHours(3);
    /** 한 번에 훑는 후보 멤버 상한. 넘으면 로그로 알린다(다음 회차가 이어받지 않으므로 늘려야 한다). */
    private static final int CANDIDATE_LIMIT = 20_000;

    private final JdbcTemplate jdbc;
    private final ChallengeMemberRepository memberRepository;
    private final ChallengeRepository challengeRepository;
    private final VerificationConfigFactory configFactory;
    private final PushSender pushSender;
    private final Clock clock;
    private final Counter sent;

    public SyncWakePushService(JdbcTemplate jdbc, ChallengeMemberRepository memberRepository,
                               ChallengeRepository challengeRepository, VerificationConfigFactory configFactory,
                               PushSender pushSender, Clock clock, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.memberRepository = memberRepository;
        this.challengeRepository = challengeRepository;
        this.configFactory = configFactory;
        this.pushSender = pushSender;
        this.clock = clock;
        this.sent = Counter.builder("verification.sync_wake.sent")
                .description("sync 가 끊겨 앱을 깨우는 무음 푸시를 보낸 사용자 수").register(registry);
    }

    @SchedulerLock(name = "SyncWakePushService.wakeStaleUsers", lockAtMostFor = "PT30M", lockAtLeastFor = "PT5M")
    @Scheduled(cron = "0 0 15,20 * * *", zone = "Asia/Seoul")
    public void wakeStaleUsers() {
        Set<UUID> users = findStaleUsers();
        for (UUID userId : users) {
            // 전송 실패는 PushSender 가 삼킨다 — 한 사람의 토큰 문제가 나머지 발송을 막지 않는다.
            pushSender.sendSilent(userId, SilentPush.syncRequired());
        }
        sent.increment(users.size());
        log.info("sync_wake_push sent={}", users.size());
    }

    /** 깨울 사용자. 발송과 나눠 둔 건 시험이 대상 선정만 따로 확인하기 위해서다. */
    Set<UUID> findStaleUsers() {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, KST);
        // 사용자 단위로 「최근 sync 가 없다」를 본다 — 다른 방 멤버 행으로 최근 sync 가 찍혔으면 앱은 살아 있다.
        List<UUID> memberIds = jdbc.query(
                "SELECT m.id FROM challenge_members m " +
                        "JOIN challenges c ON c.id = m.challenge_id " +
                        "LEFT JOIN VerificationDaily d ON d.challengeMemberId = m.id AND d.targetDate = ? " +
                        "WHERE m.status = 'ACTIVE' AND m.setup_status = 'READY' " +
                        "AND c.status = 'ACTIVE' AND c.deleted_at IS NULL " +
                        "AND (d.status IS NULL OR d.status = 'PENDING') " +
                        "AND NOT EXISTS (SELECT 1 FROM challenge_members r " +
                        "    WHERE r.user_id = m.user_id AND r.last_synced_at >= ?) " +
                        "LIMIT ?",
                (rs, i) -> uuid(rs.getBytes(1)),
                Date.valueOf(today), DbTime.utc(now.minus(STALE_AFTER)), CANDIDATE_LIMIT);
        if (memberIds.size() >= CANDIDATE_LIMIT) {
            log.warn("sync_wake_push 후보가 상한({})에 걸렸다 — 일부 사용자는 이번 회차에 깨우지 못한다", CANDIDATE_LIMIT);
        }
        if (memberIds.isEmpty()) return Set.of();

        List<ChallengeMember> members = memberRepository.findAllById(memberIds);
        Map<UUID, Challenge> challenges = challengeRepository.findAllById(
                        members.stream().map(ChallengeMember::getChallengeId).distinct().toList())
                .stream().collect(Collectors.toMap(Challenge::getId, Function.identity()));

        Set<UUID> users = new LinkedHashSet<>();
        for (ChallengeMember m : members) {
            Challenge c = challenges.get(m.getChallengeId());
            if (c == null) continue;
            VerificationConfig config = configFactory.build(c);
            // 수동 인증 방은 sync 와 무관하다. 오늘이 인증일이 아니면(요일·기간 밖) 깨울 이유가 없다.
            if (config.isManual()) continue;
            if (VerificationTargetDays.of(config, c, m, today) != VerificationTargetDays.Disposition.EVALUATE) continue;
            users.add(m.getUserId());
        }
        return users;
    }

    private static UUID uuid(byte[] b) {
        ByteBuffer buf = ByteBuffer.wrap(b);
        return new UUID(buf.getLong(), buf.getLong());
    }
}
