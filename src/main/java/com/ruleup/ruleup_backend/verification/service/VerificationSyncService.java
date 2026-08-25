package com.ruleup.ruleup_backend.verification.service;
import com.ruleup.ruleup_backend.common.verification.*;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeStatus;
import com.ruleup.ruleup_backend.challenge.service.ChallengeQueryService;
import com.ruleup.ruleup_backend.challenge.stats.ChallengeStatsRefreshRequested;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.verification.domain.*;
import com.ruleup.ruleup_backend.verification.config.VerificationProperties;
import com.ruleup.ruleup_backend.verification.dto.SyncRequest;
import com.ruleup.ruleup_backend.verification.dto.SyncResponse;
import com.ruleup.ruleup_backend.verification.evaluator.DayContext;
import com.ruleup.ruleup_backend.verification.evaluator.EvaluationOutcome;
import com.ruleup.ruleup_backend.verification.evaluator.MethodEvaluator;
import com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository;
import com.ruleup.ruleup_backend.verification.repository.VerificationMethodResultRepository;
import com.ruleup.ruleup_backend.verification.signal.SignalType;
import com.ruleup.ruleup_backend.common.verification.GeoAnchor;
import com.ruleup.ruleup_backend.verification.signal.DaySignals;
import com.ruleup.ruleup_backend.verification.signal.SyncSignal;
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
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
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
    private final VerificationSignalIngestService signalIngest;
    private final MemberSettingsResolver settingsResolver;
    private final VerificationMemberSetup memberSetup;
    private final VerificationConfigFactory configFactory;
    private final VerificationProgressService progressService;
    private final ApplicationEventPublisher eventPublisher;
    private final com.ruleup.ruleup_backend.user.UserRepository userRepository;
    private final com.ruleup.ruleup_backend.common.web.CountryResolver countryResolver;
    private final VerificationProperties properties;
    private final Map<VerificationMethod, MethodEvaluator> evaluators;

    public VerificationSyncService(ChallengeQueryService challengeQuery,
                                   VerificationDailyRepository dailyRepo,
                                   VerificationMethodResultRepository methodResultRepo,
                                   SyncRateLimiter rateLimiter,
                                   VerificationSignalIngestService signalIngest,
                                   MemberSettingsResolver settingsResolver,
                                   VerificationMemberSetup memberSetup,
                                   VerificationConfigFactory configFactory,
                                   VerificationProgressService progressService,
                                   ApplicationEventPublisher eventPublisher,
                                   com.ruleup.ruleup_backend.user.UserRepository userRepository,
                                   com.ruleup.ruleup_backend.common.web.CountryResolver countryResolver,
                                   VerificationProperties properties,
                                   List<MethodEvaluator> evaluatorList) {
        this.challengeQuery = challengeQuery;
        this.dailyRepo = dailyRepo;
        this.methodResultRepo = methodResultRepo;
        this.rateLimiter = rateLimiter;
        this.signalIngest = signalIngest;
        this.settingsResolver = settingsResolver;
        this.memberSetup = memberSetup;
        this.configFactory = configFactory;
        this.progressService = progressService;
        this.eventPublisher = eventPublisher;
        this.userRepository = userRepository;
        this.countryResolver = countryResolver;
        this.properties = properties;
        this.evaluators = evaluatorList.stream()
                .collect(Collectors.toMap(MethodEvaluator::method, e -> e, (a, b) -> a));
    }

    @Transactional
    public SyncResponse sync(UUID userId, SyncRequest req) {
        if (req == null) throw new BusinessException(ErrorCode.INVALID_SIGNAL_PAYLOAD);
        // 복구 전송(backlog)은 별도 허용치 — 평상시 간격을 그대로 적용하면 밀린 구간을 올릴 수가 없다.
        rateLimiter.check(userId.toString(), Boolean.TRUE.equals(req.backlog()));
        validateEnvelope(req);
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

        // 원본 저장 + 영속 멱등. 평가에는 이번에 처음 받은 신호만 넘긴다 —
        // 재전송된 구간이 체류·사용 시간에 다시 더해지지 않게 하는 경계다.
        VerificationSignalIngestService.Ingested ingested = signalIngest.ingest(userId, signals, now);
        List<SyncSignal> fresh = ingested.accepted();

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

            // 유예 구간(어제 귀속·미확정)에 늦게 도착한 신호를 먼저 반영한다.
            // 귀속일이 끝났어도 확정 전이면 발생 시각이 맞는 신호는 그대로 인정한다(인증 정책 §2 지연 데이터).
            boolean graceChanged = evaluateGraceDay(member, challenge, config, fresh, gaps, today, now);

            VerificationDaily daily = loadOrCreateDaily(member, challenge, today);
            VerificationStatus before = daily.getStatus();
            VerificationStatus todayStatus = processMember(member, challenge, config, daily, fresh, gaps, today, now);

            progressService.updateAfterSync(member, todayStatus, now);
            if (becameFinal(before, todayStatus) || graceChanged) {
                eventPublisher.publishEvent(ChallengeStatsRefreshRequested.of(
                        challenge.getId(), "AUTO_VERIFICATION_FINALIZED"));
            }
            updated.add(new SyncResponse.UpdatedChallenge(
                    member.getChallengeId().toString(),
                    TodayStatusView.of(todayStatus, today, daily.getFailureReason(),
                            VerificationPolarity.of(config), now),
                    member.getProgressRate()));
        }
        // sync_result — 자동 판정 커버리지·중복 비율·압축 도입 판단의 1차 근거(로깅 스펙 §9).
        log.info("sync_result userId={} signalCount={} dedupDropped={} ignoredTypes={} gapReasons={} " +
                        "activeMembers={} updated={} backlog={}",
                userId, signals.size(), ingested.droppedCount(), ignored,
                gaps.stream().map(SyncRequest.Gap::reason).filter(java.util.Objects::nonNull).distinct().toList(),
                members.size(), updated.size(), Boolean.TRUE.equals(req.backlog()));
        // flushIntervalSec: 기기 스펙 기반 산정값을 매 ACK마다 전체값으로 회신(§6 제어 모델).
        // maxPayloadBytes: 클라가 이 값을 보고 전송 구간을 쪼갠다(설정값, 실측 후 조정).
        com.ruleup.ruleup_backend.user.domain.User user = userRepository.findById(userId).orElse(null);
        backfillCountry(user, req.timeZone());
        int flushIntervalSec = FlushIntervalPolicy.forUser(user);
        return new SyncResponse(
                ZonedDateTime.ofInstant(now, KST).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                flushIntervalSec, updated, ignored, properties.maxPayloadBytes(), ingested.droppedCount());
    }

    /**
     * 국가 코드 백필. 가입·로그인 때 해석에 실패해 비어 있는 유저를, sync가 들고 오는 기기 타임존으로 채운다.
     * 이미 값이 있으면 건드리지 않으므로 주기 sync가 매번 쓰기를 만들지 않는다.
     */
    private void backfillCountry(com.ruleup.ruleup_backend.user.domain.User user, String timeZone) {
        if (user == null || user.getCountryCode() != null) return;
        user.updateCountryCode(countryResolver.resolveFor(null, null, timeZone));
    }

    /**
     * 봉투 필수값 검증.
     *
     * <p>{@code coveredFrom}/{@code coveredUntil}은 "이 구간의 신호를 빠짐없이 담았다"는 <b>선언</b>이라 필수다.
     * 이게 없으면 서버는 "신호가 없다"와 "아직 안 왔다"를 구분할 수 없어 판정을 확정할 시점을 잡지 못한다.
     */
    private void validateEnvelope(SyncRequest req) {
        if (req.deviceTimeMillis() == null
                || req.coveredFrom() == null || req.coveredUntil() == null
                || req.coveredUntil() < req.coveredFrom()) {
            throw new BusinessException(ErrorCode.INVALID_SIGNAL_PAYLOAD);
        }
    }

    private boolean becameFinal(VerificationStatus before, VerificationStatus after) {
        return after.isTerminal() && before != after;
    }

    /**
     * 유예 구간에 남아 있는 어제 귀속 건을 다시 평가한다.
     *
     * <p>귀속일이 끝나도 확정까지 하루가 더 있고, 그 사이 절전·오프라인으로 밀렸던 신호가 올라온다.
     * 그 신호를 반영하지 않으면 유예 구간이 이름뿐이다.
     *
     * <p>어제 판정 행이 <b>없어도</b> 연다. 행은 sync 가 만들기 때문에, 어제 앱을 한 번도 켜지 않은
     * 사용자는 행이 없다 — 여기서 열지 않으면 "그날 다녀왔는데 다음 날 올렸다"가 통째로 버려진다.
     * 대상 날짜가 아니었다면 {@link VerificationTargetDays} 가 걸러 NOT_TARGET 으로 남는다.
     *
     * @return 이 재평가로 어제 건이 확정됐으면 true
     */
    private boolean evaluateGraceDay(ChallengeMember member, Challenge challenge, VerificationConfig config,
                                     List<SyncSignal> fresh, List<SyncRequest.Gap> gaps,
                                     LocalDate today, Instant now) {
        LocalDate yesterday = today.minusDays(1);
        if (VerificationDeadlines.finalizeDue(yesterday, now)) return false;   // 확정 배치 몫
        if (VerificationTargetDays.of(config, challenge, member, yesterday)
                != VerificationTargetDays.Disposition.EVALUATE) {
            return false;   // 대상 아닌 날에 행을 만들지 않는다
        }

        VerificationDaily daily = loadOrCreateDaily(member, challenge, yesterday);
        if (daily.isTerminal()) return false;

        VerificationStatus before = daily.getStatus();
        VerificationStatus after = processMember(member, challenge, config, daily, fresh, gaps, yesterday, now);
        if (!becameFinal(before, after)) return false;
        progressService.recount(member);
        return true;
    }

    /**
     * 그 날짜의 판정 행을 잡는다(없으면 개시). 확정·이의 마감은 여는 즉시 세운다 —
     * 귀속일만으로 정해지고 평가 결과에 따라 흔들리지 않아야 하기 때문이다.
     */
    private VerificationDaily loadOrCreateDaily(ChallengeMember member, Challenge challenge, LocalDate targetDate) {
        VerificationDaily daily = dailyRepo.findByChallengeMemberIdAndTargetDate(member.getId(), targetDate)
                .orElseGet(() -> dailyRepo.save(
                        VerificationDaily.open(member.getId(), challenge.getId(), member.getUserId(), targetDate)));
        if (daily.getFinalizeAfter() == null) daily.applyWindow(daily.getWindowClosesAt());
        return daily;
    }

    private VerificationStatus processMember(ChallengeMember member, Challenge challenge, VerificationConfig config,
                                             VerificationDaily daily, List<SyncSignal> signals,
                                             List<SyncRequest.Gap> gaps, LocalDate today, Instant now) {
        // 확정 이후 도착분은 저장만 하고 판정에 쓰지 않는다(인증 정책 §2 지연 데이터). 구제는 이의제기로만.
        if (daily.isTerminal()) return daily.getStatus();
        VerificationTargetDays.Disposition disp =
                VerificationTargetDays.of(config, challenge, member, today);
        if (disp == VerificationTargetDays.Disposition.NOT_TARGET) {
            daily.recordResult(VerificationStatus.NOT_TARGET, null, null, null);
            return VerificationStatus.NOT_TARGET;
        }
        if (disp == VerificationTargetDays.Disposition.NOT_REQUIRED) {
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

        // 과거 날짜는 그 날 적용되던 설정으로 평가한다 — 유예 구간에 장소를 바꿔도 어제 판정이 흔들리지 않게.
        List<String> memberScreenApps = settingsResolver.screenAppPackagesOn(member, today);
        List<GeoAnchor> memberAnchors = settingsResolver.anchorsOn(member, today);
        // 신호는 도착 시각이 아니라 발생 시각으로 귀속한다 — 한 배치에 어제치와 오늘치가 섞여 온다.
        List<SyncSignal> ofDay = DaySignals.forDate(signals, today, KST);
        DayContext ctx = new DayContext(today, KST, now, config, ofDay, prior,
                memberAnchors, memberScreenApps, member.getId().toString());
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
            mr = VerificationMethodResult.create(daily.getId(), method.name(), VerificationPolarity.of(config), true);
        }
        mr.evaluate(outcome.status(), evidence, now);
        methodResultRepo.save(mr);

        // 이 지점 도달 시 daily 는 아직 확정되지 않았다(확정된 경우 processMember 초입에서 early-return).
        // 확정·이의 마감은 귀속일만으로 정해진다 — 창 닫힘 시각은 표시용으로만 갱신한다.
        daily.applyWindow(outcome.windowClosesAt());

        if (outcome.isFailExpected()) {
            // 위반·미달이 확인됐어도 귀속일 중에는 실패로 저장하지 않는다(인증 정책 §2).
            // 늦게 도착하는 이탈·해제 신호로 확정 전까지 뒤집힐 수 있어서다. 최종 실패는 확정 배치가 만든다.
            daily.recordFailExpected(method.name(), outcome.failureReason());
        } else {
            String contributing = (outcome.status() == VerificationStatus.SUCCESS) ? method.name() : null;
            Instant verifiedAt = (outcome.status() == VerificationStatus.SUCCESS) ? now : null;
            daily.recordResult(outcome.status(), contributing, null, verifiedAt);
            if (config.isFrequency() && outcome.status() == VerificationStatus.SUCCESS) {
                member.incrementPeriodCompleted();   // 빈도형: 주기 완료 +1 (미확정 상태에서 첫 SUCCESS 전이 1회)
            }
        }
        return daily.getStatus();
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

}
