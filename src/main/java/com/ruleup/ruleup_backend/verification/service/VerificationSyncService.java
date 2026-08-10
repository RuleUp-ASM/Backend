package com.ruleup.ruleup_backend.verification.service;
import com.ruleup.ruleup_backend.common.verification.*;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeStatus;
import com.ruleup.ruleup_backend.challenge.service.ChallengeQueryService;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.verification.domain.*;
import com.ruleup.ruleup_backend.verification.dto.SyncRequest;
import com.ruleup.ruleup_backend.verification.dto.SyncResponse;
import com.ruleup.ruleup_backend.verification.evaluator.DayContext;
import com.ruleup.ruleup_backend.verification.evaluator.EvaluationOutcome;
import com.ruleup.ruleup_backend.verification.evaluator.MethodEvaluator;
import com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository;
import com.ruleup.ruleup_backend.verification.repository.VerificationMethodResultRepository;
import com.ruleup.ruleup_backend.verification.signal.SignalType;
import com.ruleup.ruleup_backend.verification.signal.SyncSignal;
import com.ruleup.ruleup_backend.common.event.RoutineFailureConfirmed;
import com.ruleup.ruleup_backend.common.event.PermissionGapDetected;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 인증 sync 처리(§3.1) — 인증 엔진의 심장.
 *  흐름: 레이트리밋 → 페이로드 검증 → ignoredSignalTypes → 내 ACTIVE 멤버별로
 *        대상일 판정 → 평가기 라우팅 → verification_daily/method_result upsert(증분·멱등)
 *        → 진행률 비정규화 갱신 → updatedChallenges 회신.
 *  - 별도 크론 없음: 이 sync 요청 자체가 평가 트리거(§2.2). 확정(잠금)만 배치가 별도(§2.14).
 *  - 단일 method MVP: daily 상태 = primary method 상태(결합기는 다중 method 도입 시).
 */
@Service
public class VerificationSyncService {

    private static final Logger log = LoggerFactory.getLogger(VerificationSyncService.class);

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 누적 일괄 상한: 신호 배열 총 개수(초과 시 413 SYNC_PAYLOAD_TOO_LARGE, 클라는 분할 재전송). */
    private static final int MAX_SIGNALS_PER_SYNC = 5000;
    private static final Set<String> KNOWN_SIGNAL_TYPES = Stream.concat(
            Arrays.stream(SignalType.values()).map(Enum::name),
            Stream.of("GEOFENCE_TRANSITION")   // Android 와이어 별칭
    ).collect(Collectors.toUnmodifiableSet());

    private final ChallengeQueryService challengeQuery;
    private final VerificationDailyRepository dailyRepo;
    private final VerificationMethodResultRepository methodResultRepo;
    private final SyncRateLimiter rateLimiter;
    private final VerificationMemberSetup memberSetup;
    private final VerificationConfigFactory configFactory;
    private final VerificationProgressService progressService;
    private final ApplicationEventPublisher eventPublisher;
    private final com.ruleup.ruleup_backend.user.UserRepository userRepository;
    private final Map<VerificationMethod, MethodEvaluator> evaluators;

    public VerificationSyncService(ChallengeQueryService challengeQuery,
                                   VerificationDailyRepository dailyRepo,
                                   VerificationMethodResultRepository methodResultRepo,
                                   SyncRateLimiter rateLimiter,
                                   VerificationMemberSetup memberSetup,
                                   VerificationConfigFactory configFactory,
                                   VerificationProgressService progressService,
                                   ApplicationEventPublisher eventPublisher,
                                   com.ruleup.ruleup_backend.user.UserRepository userRepository,
                                   List<MethodEvaluator> evaluatorList) {
        this.challengeQuery = challengeQuery;
        this.dailyRepo = dailyRepo;
        this.methodResultRepo = methodResultRepo;
        this.rateLimiter = rateLimiter;
        this.memberSetup = memberSetup;
        this.configFactory = configFactory;
        this.progressService = progressService;
        this.eventPublisher = eventPublisher;
        this.userRepository = userRepository;
        this.evaluators = evaluatorList.stream()
                .collect(Collectors.toMap(MethodEvaluator::method, e -> e, (a, b) -> a));
    }

    @Transactional
    public SyncResponse sync(UUID userId, SyncRequest req) {
        rateLimiter.check(userId.toString());
        if (req == null || req.deviceTimeMillis() == null) {
            throw new BusinessException(ErrorCode.INVALID_SIGNAL_PAYLOAD);
        }
        List<SyncSignal> signals = (req.signals() != null) ? req.signals() : List.of();
        if (signals.size() > MAX_SIGNALS_PER_SYNC) {
            throw new BusinessException(ErrorCode.SYNC_PAYLOAD_TOO_LARGE);   // 413 — 클라는 분할 재전송
        }
        List<String> ignored = signals.stream()
                .map(SyncSignal::type)
                .filter(t -> t == null || !KNOWN_SIGNAL_TYPES.contains(t))
                .distinct().toList();
        List<SyncRequest.Gap> gaps = (req.gaps() != null) ? req.gaps() : List.of();   // §8.5 권한 공백 소비 입력

        LocalDate today = LocalDate.now(KST);
        Instant now = Instant.now();

        List<ChallengeMember> members = challengeQuery.findActiveMemberships(userId);
        List<SyncResponse.UpdatedChallenge> updated = new ArrayList<>();

        for (ChallengeMember member : members) {
            Challenge challenge = challengeQuery.findActiveChallenge(member.getChallengeId()).orElse(null);
            if (challenge == null || challenge.getStatus() != ChallengeStatus.ACTIVE) continue;

            VerificationConfig config = configFactory.build(challenge);
            if (config.isManual()) continue;   // 수동 챌린지: 자동 평가 대상 아님

            if (member.getTargetDays() == 0) memberSetup.apply(member, challenge, config);

            // v2: 셋업 전(PENDING_SETUP)이면 신호는 수용하되 평가 skip("권한 없는데 FAILED" 원천 차단, §4·§11.1)
            if (!member.isSetupReady()) continue;

            VerificationDaily daily = loadOrCreateDaily(member, challenge, today);
            VerificationStatus todayStatus = processMember(member, challenge, config, daily, signals, gaps, today, now);

            progressService.updateAfterSync(member, todayStatus, now);
            updated.add(new SyncResponse.UpdatedChallenge(
                    member.getChallengeId().toString(), todayStatus.name(), member.getProgressRate()));
        }
        if (log.isDebugEnabled()) {
            log.debug("sync userId={} signals={} ignored={} activeMembers={} updated={}",
                    userId, signals.size(), ignored, members.size(), updated.size());
        }
        // flushIntervalSec: 기기 스펙 기반 산정값을 매 ACK마다 전체값으로 회신(§6 제어 모델).
        int flushIntervalSec = FlushIntervalPolicy.forUser(userRepository.findById(userId).orElse(null));
        return new SyncResponse(now.toString(), flushIntervalSec, updated, ignored);
    }

    private VerificationDaily loadOrCreateDaily(ChallengeMember member, Challenge challenge, LocalDate today) {
        return dailyRepo.findByChallengeMemberIdAndTargetDate(member.getId(), today)
                .orElseGet(() -> dailyRepo.save(
                        VerificationDaily.open(member.getId(), challenge.getId(), member.getUserId(), today)));
    }

    private VerificationStatus processMember(ChallengeMember member, Challenge challenge, VerificationConfig config,
                                             VerificationDaily daily, List<SyncSignal> signals,
                                             List<SyncRequest.Gap> gaps, LocalDate today, Instant now) {
        // 이미 잠김 → 멱등, 재평가 안 함(FAILED→SUCCESS 역전 금지 포함)
        if (daily.getStatus() == VerificationStatus.SUCCESS || daily.getStatus() == VerificationStatus.FAILED) {
            return daily.getStatus();
        }
        Disposition disp = disposition(config, challenge, member, today);
        if (disp == Disposition.NOT_TARGET) {
            daily.recordResult(VerificationStatus.NOT_TARGET, null, null, null);
            return VerificationStatus.NOT_TARGET;
        }
        if (disp == Disposition.NOT_REQUIRED) {
            daily.recordResult(VerificationStatus.NOT_REQUIRED, null, null, null);
            return VerificationStatus.NOT_REQUIRED;
        }
        return evaluateAndApply(member, challenge, config, daily, signals, gaps, today, now);
    }

    private VerificationStatus evaluateAndApply(ChallengeMember member, Challenge challenge, VerificationConfig config,
                                                VerificationDaily daily, List<SyncSignal> signals,
                                                List<SyncRequest.Gap> gaps, LocalDate today, Instant now) {
        VerificationMethod method = config.primaryMethod();
        MethodEvaluator evaluator = evaluators.get(method);
        if (evaluator == null) {
            // 아직 미구현 method(이번 단계 WAKE만) → 평가 보류, PENDING 유지
            return (daily.getStatus() != null) ? daily.getStatus() : VerificationStatus.PENDING;
        }

        VerificationMethodResult mr = methodResultRepo
                .findByVerificationDailyIdAndMethod(daily.getId(), method.name()).orElse(null);
        Map<String, Object> prior = (mr != null) ? mr.getEvidence() : null;

        List<String> memberScreenApps = member.effectiveScreenApps(today).stream()
                .map(com.ruleup.ruleup_backend.common.verification.ScreenApp::packageName)
                .toList();
        DayContext ctx = new DayContext(today, KST, now, config, signals, prior,
                member.getAnchors(), memberScreenApps, member.getId().toString());
        EvaluationOutcome outcome = evaluator.evaluate(ctx);

        // ③ 권한 공백(gaps) 반영: 신호 없이 PENDING이고 해당 신호타입에 비회복 권한 공백이 있으면
        //    마감 배치가 NO_SIGNAL_RECEIVED 대신 PERMISSION_MISSING으로 확정하도록 힌트를 남긴다(§8.5).
        Map<String, Object> evidence = outcome.evidence();
        if (outcome.status() == VerificationStatus.PENDING && permissionGap(gaps, method, today)) {
            evidence = (evidence != null) ? new HashMap<>(evidence) : new HashMap<>();
            evidence.putIfAbsent("pendingReason", "PERMISSION_MISSING");
            // 실시간 권한공백 → 고스트 푸시 큐 적재 트리거(§8.5). 리스너가 같은 트랜잭션에서 outbox만 적재(발송은 별도 스윕).
            eventPublisher.publishEvent(new PermissionGapDetected(
                    member.getUserId(), member.getChallengeId(), method.name(), today, now));
        }

        if (mr == null) {
            mr = VerificationMethodResult.create(daily.getId(), method.name(), polarityOf(config), true);
        }
        mr.evaluate(outcome.status(), evidence, now);
        methodResultRepo.save(mr);

        // 이 지점 도달 시 daily 는 아직 SUCCESS/FAILED 로 잠기지 않음(잠긴 경우 processMember 초입에서 early-return).
        Instant windowCloses = outcome.windowClosesAt();
        Instant finalizeAfter = (windowCloses != null)
                ? windowCloses.plus(maxLagHours(config), ChronoUnit.HOURS) : null;
        daily.applyWindow(windowCloses, finalizeAfter);

        if (outcome.status() == VerificationStatus.FAILED) {
            // 제약형 위반 등 sync 시점 실패(§8.7). 그룹은 잠정 실패(1일 이의 제기 창), 솔로는 즉시 확정.
            if (challenge != null && challenge.isGroup()) {
                daily.recordProvisionalFailure(method.name(), outcome.failureReason(),
                        now.plus(VerificationDaily.OBJECTION_WINDOW_DAYS, ChronoUnit.DAYS));   // 온도 미반영, 이벤트 없음(확정 아님)
            } else {
                daily.recordResult(VerificationStatus.FAILED, null, outcome.failureReason(), now);
                // 솔로 즉시 확정 → 감시자 통지 적재(§9). 다음 sync는 line 126에서 스킵되어 중복 없음.
                eventPublisher.publishEvent(new RoutineFailureConfirmed(
                        member.getChallengeId(), member.getUserId(), today, now));
            }
        } else {
            String contributing = (outcome.status() == VerificationStatus.SUCCESS) ? method.name() : null;
            Instant verifiedAt = (outcome.status() == VerificationStatus.SUCCESS) ? now : null;
            daily.recordResult(outcome.status(), contributing, outcome.failureReason(), verifiedAt);
            if (config.isFrequency() && outcome.status() == VerificationStatus.SUCCESS) {
                member.incrementPeriodCompleted();   // 빈도형: 주기 완료 +1 (미잠금 상태에서 첫 SUCCESS 전이 1회)
            }
        }
        // 캐시(todayStatus)에는 실제 저장 상태를 반영(그룹 잠정 실패는 FAILED_PROVISIONAL).
        return daily.getStatus();
    }

    private enum Disposition { NOT_TARGET, NOT_REQUIRED, EVALUATE }

    private Disposition disposition(VerificationConfig config, Challenge challenge, ChallengeMember member, LocalDate today) {
        if (config.isFrequency()) {
            Integer done = member.getCurPeriodCompleted();
            Integer need = member.getPeriodTarget();
            if (done != null && need != null && done >= need) return Disposition.NOT_REQUIRED;
            return Disposition.EVALUATE;   // 빈도형은 모든 날 eligible
        }
        List<String> repeat = challenge.getRepeatDays();
        boolean target = repeat != null && repeat.contains(WeekdayCodes.code(today.getDayOfWeek()));
        return target ? Disposition.EVALUATE : Disposition.NOT_TARGET;
    }


    private int maxLagHours(VerificationConfig config) {
        return switch (config.primaryMethod()) {
            case WAKE -> (config.wake() != null) ? config.wake().maxSignalLagHours() : 2;
            case SCREEN_TIME -> (config.screenTime() != null) ? config.screenTime().maxSignalLagHours() : 1;
            case GPS_PRESENCE, GPS_DISTANCE -> (config.gps() != null) ? config.gps().maxSignalLagHours() : 1;
            case HEALTH -> (config.health() != null) ? config.health().maxSignalLagHours() : 2;
            case SLEEP -> (config.sleep() != null) ? config.sleep().maxSignalLagHours() : 12;
            default -> 1;
        };
    }

    /** 해당 method의 신호타입에 대해, 당일과 겹치는 비회복(recoverable=false) 권한 공백이 있는지(§8.5). */
    private boolean permissionGap(List<SyncRequest.Gap> gaps, VerificationMethod method, LocalDate day) {
        if (gaps == null || gaps.isEmpty()) return false;
        Set<String> types = signalTypesFor(method);
        if (types.isEmpty()) return false;
        long dayStart = day.atStartOfDay(KST).toInstant().toEpochMilli();
        long dayEnd = day.plusDays(1).atStartOfDay(KST).toInstant().toEpochMilli();
        for (SyncRequest.Gap g : gaps) {
            if (g == null || Boolean.TRUE.equals(g.recoverable())) continue;             // 회복 가능 → 유예(§0.5)
            if (g.reason() == null || !g.reason().toUpperCase().contains("PERMISSION")) continue;
            if (g.signalType() != null && !types.contains(g.signalType().toUpperCase())) continue;
            if (g.fromMillis() != null && g.toMillis() != null
                    && (g.toMillis() < dayStart || g.fromMillis() > dayEnd)) continue;    // 당일과 무겹침
            return true;
        }
        return false;
    }

    /** method → 그 판정에 쓰이는 신호타입(대문자). gap.signalType 매칭용. */
    private static Set<String> signalTypesFor(VerificationMethod method) {
        return switch (method) {
            case GPS_PRESENCE, GPS_DISTANCE -> Set.of("GEOFENCE", "GEOFENCE_TRANSITION", "LOCATION");
            case HEALTH -> Set.of("HEALTH");
            case SCREEN_TIME, WAKE -> Set.of("SCREEN_TIME", "USAGE");
            case SLEEP -> Set.of("SLEEP");
            default -> Set.of();
        };
    }

    private Polarity polarityOf(VerificationConfig config) {
        return switch (config.primaryMethod()) {
            case WAKE -> (config.wake() != null && config.wake().polarity() != null) ? config.wake().polarity() : Polarity.ACHIEVEMENT;
            case SCREEN_TIME -> (config.screenTime() != null && config.screenTime().polarity() != null) ? config.screenTime().polarity() : Polarity.ACHIEVEMENT;
            case GPS_PRESENCE, GPS_DISTANCE -> (config.gps() != null && config.gps().polarity() != null) ? config.gps().polarity() : Polarity.ACHIEVEMENT;
            case HEALTH -> (config.health() != null && config.health().polarity() != null) ? config.health().polarity() : Polarity.ACHIEVEMENT;
            case SLEEP -> (config.sleep() != null && config.sleep().polarity() != null) ? config.sleep().polarity() : Polarity.ACHIEVEMENT;
            default -> Polarity.ACHIEVEMENT;
        };
    }
}
