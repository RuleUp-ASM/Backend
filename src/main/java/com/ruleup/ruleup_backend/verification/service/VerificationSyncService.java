package com.ruleup.ruleup_backend.verification.service;
import com.ruleup.ruleup_backend.common.verification.*;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeStatus;
import com.ruleup.ruleup_backend.challenge.service.ChallengeQueryService;
import com.ruleup.ruleup_backend.challenge.stats.ChallengeStatsRefreshRequested;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.notification.NotificationEvent;
import com.ruleup.ruleup_backend.notification.NotificationPublisher;
import com.ruleup.ruleup_backend.notification.domain.NotificationParams;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import com.ruleup.ruleup_backend.verification.domain.*;
import com.ruleup.ruleup_backend.verification.config.VerificationProperties;
import com.ruleup.ruleup_backend.verification.config.SyncPayloadSizeFilter;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
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
import java.util.concurrent.atomic.AtomicLong;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 인증 sync 처리(§3.1) — 인증 엔진의 심장.
 *  흐름: 레이트리밋 → 페이로드 검증 → 원본 적재 → <b>그 귀속일 원본 전량 재조회</b> →
 *        내 ACTIVE 멤버별로 대상일 판정 → 평가기 라우팅 → verification_daily/method_result upsert
 *        → 진행률 비정규화 갱신 → updatedChallenges 회신.
 *  - 판정 입력은 <b>이번 요청의 신호가 아니라 저장된 원본</b>이다. 요청분만 보면 분할 전송 순서가
 *    바뀔 때 짝을 못 찾은 이벤트가 버려진다(백엔드 4-3 「나누어 보낸 요청은 순서가 바뀌어도 되도록」).
 *  - 별도 크론 없음: 이 sync 요청 자체가 평가 트리거(§2.2). 확정(잠금)만 배치가 별도(§2.14).
 *  - 단일 method MVP: daily 상태 = primary method 상태(결합기는 다중 method 도입 시).
 */
@Service
public class VerificationSyncService {

    private static final Logger log = LoggerFactory.getLogger(VerificationSyncService.class);

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 누적 일괄 상한: 신호 배열 총 개수(초과 시 413 SYNC_PAYLOAD_TOO_LARGE, 클라는 분할 재전송). */
    private static final int MAX_SIGNALS_PER_SYNC = 5000;
    /** 신호 하나의 대략적인 직렬화 크기. 본문 크기 <b>분포</b>를 보기 위한 환산 계수다. */
    private static final int APPROX_BYTES_PER_SIGNAL = 256;
    private static final Set<String> KNOWN_SIGNAL_TYPES = Stream.concat(
            Arrays.stream(SignalType.values()).map(Enum::name),
            Stream.of("GEOFENCE_TRANSITION")   // Android 와이어 별칭
    ).collect(Collectors.toUnmodifiableSet());

    private final com.ruleup.ruleup_backend.verification.service.DeviceSyncPolicyService syncPolicy;
    private final ChallengeQueryService challengeQuery;
    private final VerificationDailyRepository dailyRepo;
    private final VerificationMethodResultRepository methodResultRepo;
    private final SyncRateLimiter rateLimiter;
    private final VerificationSignalIngestService signalIngest;
    private final VerificationSignalReader signalReader;
    private final MemberSettingsResolver settingsResolver;
    private final SignalTrustGate trustGate;
    private final VerificationMemberSetup memberSetup;
    private final VerificationConfigFactory configFactory;
    private final VerificationProgressService progressService;
    private final NotificationPublisher notificationPublisher;
    private final ApplicationEventPublisher eventPublisher;
    private final com.ruleup.ruleup_backend.user.UserRepository userRepository;
    private final com.ruleup.ruleup_backend.common.web.CountryResolver countryResolver;
    private final VerificationProperties properties;
    private final SignalExclusionRecorder exclusionRecorder;
    private final AnomalyEventRecorder anomalyRecorder;
    private final LocationPurgeService locationPurge;
    private final VerificationSyncSessionStore sessionStore;
    private final SignalConsentGate consentGate;
    private final VerificationMetrics metrics;
    private final Map<VerificationMethod, MethodEvaluator> evaluators;

    public VerificationSyncService(DeviceSyncPolicyService syncPolicy, ChallengeQueryService challengeQuery,
                                   VerificationDailyRepository dailyRepo,
                                   VerificationMethodResultRepository methodResultRepo,
                                   SyncRateLimiter rateLimiter,
                                   VerificationSignalIngestService signalIngest,
                                   VerificationSignalReader signalReader,
                                   MemberSettingsResolver settingsResolver,
                                   SignalTrustGate trustGate,
                                   VerificationMemberSetup memberSetup,
                                   VerificationConfigFactory configFactory,
                                   VerificationProgressService progressService,
                                   NotificationPublisher notificationPublisher,
                                   ApplicationEventPublisher eventPublisher,
                                   com.ruleup.ruleup_backend.user.UserRepository userRepository,
                                   com.ruleup.ruleup_backend.common.web.CountryResolver countryResolver,
                                   VerificationProperties properties,
                                   SignalExclusionRecorder exclusionRecorder,
                                   AnomalyEventRecorder anomalyRecorder,
                                   LocationPurgeService locationPurge,
                                   VerificationSyncSessionStore sessionStore,
                                   SignalConsentGate consentGate,
                                   VerificationMetrics metrics,
                                   List<MethodEvaluator> evaluatorList) {
        this.syncPolicy = syncPolicy;
        this.challengeQuery = challengeQuery;
        this.dailyRepo = dailyRepo;
        this.methodResultRepo = methodResultRepo;
        this.rateLimiter = rateLimiter;
        this.signalIngest = signalIngest;
        this.signalReader = signalReader;
        this.settingsResolver = settingsResolver;
        this.trustGate = trustGate;
        this.memberSetup = memberSetup;
        this.configFactory = configFactory;
        this.progressService = progressService;
        this.notificationPublisher = notificationPublisher;
        this.eventPublisher = eventPublisher;
        this.userRepository = userRepository;
        this.countryResolver = countryResolver;
        this.properties = properties;
        this.exclusionRecorder = exclusionRecorder;
        this.anomalyRecorder = anomalyRecorder;
        this.locationPurge = locationPurge;
        this.sessionStore = sessionStore;
        this.consentGate = consentGate;
        this.metrics = metrics;
        this.evaluators = evaluatorList.stream()
                .collect(Collectors.toMap(MethodEvaluator::method, e -> e, (a, b) -> a));
    }

    @Transactional
    public SyncResponse sync(UUID userId, SyncRequest req) {
        long startedAt = System.nanoTime();
        if (req == null) throw new BusinessException(ErrorCode.INVALID_SIGNAL_PAYLOAD);
        // 복구 전송(backlog)은 별도 허용치 — 평상시 간격을 그대로 적용하면 밀린 구간을 올릴 수가 없다.
        boolean backlog = Boolean.TRUE.equals(req.backlog());
        // 복구 전송은 레이트리밋 허용치가 다르다. 「구간당 요청 수」를 볼 때 이 값이 분자다 —
        // 세지 않으면 복구가 정상 주기 전송을 밀어내고 있는지 밖에서 알 수 없다.
        if (backlog) metrics.backlogRequest();
        rateLimiter.check(userId.toString(), backlog);
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

        com.ruleup.ruleup_backend.user.domain.User user = userRepository.findById(userId).orElse(null);
        sessionStore.touch(userId, req.sessionId(), now);

        // 개별 동의가 없는 위치·건강 신호는 적재 이전에 떨어뜨린다. 신호 위생과 달리 이건
        // 「받아서 안 쓴다」가 아니라 「받으면 안 된다」다(공통 5-6).
        SignalConsentGate.Decision consent = consentGate.apply(userId, signals);
        List<SyncSignal> collectible = consent.accepted();

        // 원본 저장 + 영속 멱등. 못 믿을 봉투(VPN·무결성 실패·비활성 기기)의 신호는 저장하되
        // 배제 사유를 행에 새긴다 — 제외와 제재는 분리하고, 원본은 이상탐지 자료로 남긴다.
        VerificationSignalIngestService.Ingested ingested = signalIngest.ingest(userId, collectible, now,
                new VerificationSignalIngestService.Source(req.deviceId(), gateFor(user, req)));
        int gateDropped = trustGate.record(userId, req, collectible);

        // 판정 입력은 저장된 원본이다. 오늘과 유예 중인 어제를 한 번씩만 읽어 멤버들이 나눠 쓴다 —
        // 같은 사용자 신호를 챌린지별로 복제해 읽지 않는다(백엔드 4-1-1 「사용자 신호 1회 저장」).
        LocalDate yesterday = today.minusDays(1);
        Map<LocalDate, VerificationSignalReader.DaySignalSet> daySignals =
                signalReader.forDays(userId, List.of(yesterday, today));

        List<ChallengeMember> members = challengeQuery.findActiveMemberships(userId);
        List<SyncResponse.UpdatedChallenge> updated = new ArrayList<>();

        for (ChallengeMember member : members) {
            Challenge challenge = challengeQuery.findActiveChallenge(member.getChallengeId()).orElse(null);
            if (challenge == null) continue;
            // 끝난 방도 유예 구간 동안은 열어 둔다. 종료 전환이 endDate 다음 날 일어나므로
            // ACTIVE 만 받으면 마지막 활동일에 늦게 도착한 신호를 반영할 길이 없다.
            boolean graceOnly = challenge.getStatus() != ChallengeStatus.ACTIVE;
            if (graceOnly && !withinGraceOfCompleted(challenge, now)) continue;

            VerificationConfig config = configFactory.build(challenge);
            if (config.isManual()) continue;   // 수동 챌린지: 자동 평가 대상 아님

            if (member.getTargetDays() == 0) memberSetup.apply(member, challenge, config);

            // v2: 셋업 전(PENDING_SETUP)이면 신호는 수용하되 평가 skip("권한 없는데 FAILED" 원천 차단, §4·§11.1)
            if (!member.isSetupReady()) continue;

            // 유예 구간(어제 귀속·미확정)에 늦게 도착한 신호를 먼저 반영한다.
            // 귀속일이 끝났어도 확정 전이면 발생 시각이 맞는 신호는 그대로 인정한다(인증 정책 §2 지연 데이터).
            boolean graceChanged = evaluateGraceDay(member, challenge, config, daySignals, gaps, today, now);

            if (graceOnly) {
                // 방이 끝났으니 오늘은 인증 대상일이 아니다. 열지도 않은 오늘 행을 NOT_TARGET 으로
                // 만들 이유가 없어 여기서 멈춘다 — 유예분만 반영하고 회신에는 싣지 않는다.
                if (graceChanged) {
                    eventPublisher.publishEvent(ChallengeStatsRefreshRequested.of(
                            challenge.getId(), "AUTO_VERIFICATION_FINALIZED"));
                }
                continue;
            }

            VerificationDaily daily = loadOrCreateDaily(member, challenge, today);
            VerificationStatus before = daily.getStatus();
            VerificationStatus todayStatus = processMember(member, challenge, config, daily,
                    daySignals.get(today), gaps, today, now);

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
        log.info("sync_result userId={} signalCount={} dedupDropped={} ignoredTypes={} consentRejected={} " +
                        "gapReasons={} activeMembers={} updated={} backlog={}",
                userId, signals.size(), ingested.droppedCount(), ignored, consent.rejectedTypes(),
                gaps.stream().map(SyncRequest.Gap::reason).filter(java.util.Objects::nonNull).distinct().toList(),
                members.size(), updated.size(), Boolean.TRUE.equals(req.backlog()));
        // flushIntervalSec: 기기 스펙 기반 산정값을 매 ACK마다 전체값으로 회신(§6 제어 모델).
        // maxPayloadBytes: 클라가 이 값을 보고 전송 구간을 쪼갠다(설정값, 실측 후 조정).
        backfillCountry(user, req.timeZone());
        int flushIntervalSec = syncPolicy.forUser(user);
        metrics.sync(System.nanoTime() - startedAt, signals.size(), ingested.droppedCount(),
                gateDropped, consent.rejectedTypes().size());
        // 봉투의 모양 — 압축·요약 전송 도입 판단의 근거다(백엔드 7절).
        metrics.envelope(payloadBytesOf(req), (req.coveredUntil() - req.coveredFrom()) / 1000);
        return new SyncResponse(
                ZonedDateTime.ofInstant(now, KST).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                flushIntervalSec, updated, ignored, properties.maxPayloadBytes(), ingested.droppedCount(),
                consent.consentRequired());
    }

    /**
     * 이 요청의 신호에 새길 배제 사유.
     *
     * <p>비활성 기기가 먼저다 — 기기 전체를 못 믿는 경우라 신호 종류를 가리지 않는다. 예전 기기에
     * 남아 있던 백로그가 새 기기의 인증을 통과시키면 안 되기 때문이다(스펙: 「비활성 기기 신호는
     * 수신해도 판정에 쓰지 않음」). 기기를 밝히지 않은 요청은 <b>거르지 않는다</b> — 계약에 기기가
     * 없던 시절의 앱이 전부 인증 불가가 된다.
     */
    private java.util.function.Function<String, SignalExclusionReason> gateFor(
            com.ruleup.ruleup_backend.user.domain.User user, SyncRequest req) {
        var trust = trustGate.decide(req);
        if (!inactiveDevice(user, req.deviceId())) return trust;
        return type -> SignalExclusionReason.UNTRUSTED_SOURCE;
    }

    /**
     * 활성 기기가 아닌지.
     *
     * <p>기기를 밝히지 않은 요청은 <b>엄격 모드가 아니면 통과</b>시킨다(엄격 모드에서는 봉투 검증이
     * 이미 거절했다). 다만 그냥 넘기지 않고 센다 — 이 카운터가 0 으로 떨어져야 엄격 모드를 켤 수
     * 있고, 그 전에는 「검증하고 있다」고 말할 수 없다.
     *
     * <p><b>계정 쪽에 활성 기기가 없을 때</b>도 같은 문제다. 대조할 대상이 없으니 요청이 들고 온
     * 값을 그대로 믿는 셈인데, 그러면 엄격 모드를 켜도 <b>기기를 한 번도 등록하지 않은 계정은
     * 아무 값이나 적어 통과</b>한다 — 스위치만 올려서는 「AT + 활성 기기 검증」이 되지 않는다.
     * 그래서 엄격 모드에서는 <b>모르면 쓰지 않는다</b>. 관대 모드에서는 통과시키되 센다.
     */
    private boolean inactiveDevice(com.ruleup.ruleup_backend.user.domain.User user, String deviceId) {
        if (blank(deviceId)) {
            metrics.deviceIdMissing();
            return false;
        }
        String active = (user != null) ? user.getDeviceId() : null;
        if (active == null || active.isBlank()) {
            metrics.activeDeviceUnknown();
            return properties.requireActiveDevice();
        }
        return !active.equals(deviceId.trim());
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
        // 「AT + 활성 기기 검증」을 엄격히 적용하는 모드. 기기를 밝히지 않으면 <b>어느 기기 신호인지
        // 알 수 없어</b> 활성 여부를 물을 수조차 없으므로 받지 않는다. 기본값은 꺼짐 —
        // 계약에 기기가 없던 시절의 앱을 한 번에 인증 불가로 만들지 않기 위해서다.
        if (properties.requireActiveDevice() && blank(req.deviceId())) {
            throw new BusinessException(ErrorCode.INVALID_SIGNAL_PAYLOAD);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 이 요청의 본문 크기 — 크기 상한 필터가 스트림에서 <b>실제로 센 바이트</b>다.
     *
     * <p>신호 수에 고정 배수를 곱하는 근사를 쓰면 분포가 흐려진다. 측위 포인트가 잔뜩 실린
     * 신호 하나와 빈 신호 하나가 같은 크기로 잡혀, 정작 상한에 부딪히는 요청이 분포에서
     * 사라지기 때문이다 — 상한을 조정하려고 보는 값인데 조정 근거가 지워진다.
     *
     * <p>요청 문맥 밖(테스트·배치 재처리)에서는 셀 값이 없으므로 예전 근사로 물러난다.
     */
    private static long payloadBytesOf(SyncRequest req) {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            Object counted = attrs.getAttribute(
                    SyncPayloadSizeFilter.BODY_BYTES_ATTR, RequestAttributes.SCOPE_REQUEST);
            if (counted instanceof AtomicLong bytes && bytes.get() > 0) return bytes.get();
        }
        List<SyncSignal> signals = req.signals();
        return (signals != null) ? (long) signals.size() * APPROX_BYTES_PER_SIGNAL : 0L;
    }

    private boolean becameFinal(VerificationStatus before, VerificationStatus after) {
        return after.isTerminal() && before != after;
    }

    /**
     * 끝난 방이 아직 마지막 활동일의 유예 구간 안인지.
     *
     * <p>종료 전환은 endDate 다음 날 일어나는데 endDate 귀속 판정의 확정은 그 이틀 뒤다. 그 사이
     * 하루가 「방은 COMPLETED 인데 판정은 아직 열려 있는」 구간이고, 절전·오프라인으로 밀린
     * 마지막 날 신호가 올라오는 자리다. 확정 시각이 지나면 더는 열지 않는다.
     */
    private boolean withinGraceOfCompleted(Challenge challenge, Instant now) {
        LocalDate endDate = challenge.getEndDate();
        return endDate != null && !VerificationDeadlines.finalizeDue(endDate, now);
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
                                     Map<LocalDate, VerificationSignalReader.DaySignalSet> daySignals,
                                     List<SyncRequest.Gap> gaps, LocalDate today, Instant now) {
        LocalDate yesterday = today.minusDays(1);
        if (VerificationDeadlines.finalizeDue(yesterday, now)) return false;   // 확정 배치 몫
        if (VerificationTargetDays.of(config, challenge, member, yesterday)
                != VerificationTargetDays.Disposition.EVALUATE) {
            return false;   // 대상 아닌 날에 행을 만들지 않는다
        }

        VerificationDaily daily = loadOrCreateDaily(member, challenge, yesterday);
        if (daily.isTerminal()) return false;

        VerificationStatus before = daily.getStatus();
        VerificationStatus after = processMember(member, challenge, config, daily,
                daySignals.get(yesterday), gaps, yesterday, now);
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
                                             VerificationDaily daily,
                                             VerificationSignalReader.DaySignalSet daySignals,
                                             List<SyncRequest.Gap> gaps, LocalDate today, Instant now) {
        // 확정 이후 도착분은 저장만 하고 판정에 쓰지 않는다(인증 정책 §2 지연 데이터). 구제는 이의제기로만.
        // 여기 닿는 것은 <b>정상</b>이다 — 오프라인 복구·재전송이 확정된 날짜로 계속 들어온다.
        // 스펙 7절의 「확정 후 자동 정정 0건」은 이 early-return 이 구조적으로 보장하므로 따로
        // 셀 자리가 없고, 대신 그 경로의 양을 관찰값으로 남긴다.
        if (daily.isTerminal()) {
            metrics.terminalDaySignals();
            return daily.getStatus();
        }
        // 원본을 전부 읽지 못한 날은 평가하지 않는다. 잘린 값으로 「실패 예정」이나 성공을 찍으면
        // 사용자에게 잘못된 결과가 그대로 보인다 — 판정을 미루는 편이 낫다.
        if (daySignals == null || !daySignals.complete()) {
            log.warn("원본을 전부 읽지 못해 이 날 평가를 건너뛴다 userId={} targetDate={}",
                    member.getUserId(), today);
            return daily.getStatus();
        }
        List<SyncSignal> signals = daySignals.signals();
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
            // 방어 분기 — 인증 방식 6종(GPS_PRESENCE·GPS_DISTANCE·HEALTH·SCREEN_TIME·SLEEP·WAKE)은 전부
            // evaluator 가 있고 수동은 SELF_CHECK 로 접힌다(VerificationConfigFactory). 새 방식을 enum 에만
            // 먼저 추가한 경우 여기로 떨어진다 → 평가 보류, PENDING 유지
            return (daily.getStatus() != null) ? daily.getStatus() : VerificationStatus.PENDING;
        }

        VerificationMethodResult mr = methodResultRepo
                .findByVerificationDailyIdAndMethod(daily.getId(), method.name()).orElse(null);

        // 과거 날짜는 그 날 적용되던 설정으로 평가한다 — 유예 구간에 장소를 바꿔도 어제 판정이 흔들리지 않게.
        List<String> memberScreenApps = settingsResolver.screenAppPackagesOn(member, today);
        List<GeoAnchor> memberAnchors = settingsResolver.anchorsOn(member, today);
        // 신호는 도착 시각이 아니라 발생 시각으로 귀속한다 — 한 배치에 어제치와 오늘치가 섞여 온다.
        List<SyncSignal> ofDay = DaySignals.forDate(signals, today, KST);
        DayContext ctx = new DayContext(today, KST, now, config, ofDay,
                memberAnchors, memberScreenApps, member.getId().toString());
        EvaluationOutcome outcome = evaluator.evaluate(ctx);

        // ③ 권한 공백(gaps) 반영: 신호 없이 PENDING이고 해당 신호타입에 비회복 권한 공백이 있으면
        //    마감 배치가 NO_SIGNAL_RECEIVED 대신 PERMISSION_MISSING으로 확정하도록 힌트를 남긴다(§8.5).
        Map<String, Object> evidence = outcome.evidence();
        if (outcome.status() == VerificationStatus.PENDING && permissionGap(gaps, method, today)) {
            evidence = (evidence != null) ? new HashMap<>(evidence) : new HashMap<>();
            evidence.putIfAbsent("pendingReason", "PERMISSION_MISSING");
            // 실시간 권한공백 → 리스너 트리거(§8.5). 리스너는 같은 트랜잭션에서 둘을 적재한다 —
            // 고스트 푸시 outbox(발송은 별도 스윕)와 권한 재허용 고지(알림함). 둘 다 예외를
            // 삼키므로 여기 sync 평가가 그것 때문에 롤백되지 않는다.
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
            if (outcome.status() == VerificationStatus.SUCCESS) {
                // 판정에서 뺀 신호를 배제 로그로 옮긴다 — **확정 시 한 번**이다. evidence 는
                // sync 마다 누적되므로 매번 옮기면 같은 배제가 여러 행이 된다(공통 5-3).
                exclusionRecorder.recordEvaluationHygiene(
                        member.getUserId(), daily.getId(), method, evidence, now);
                // 성공 인증만 탐지 feature 로 승격한다(스펙: 실패 인증은 anomaly 데이터셋을 만들지 않음).
                anomalyRecorder.recordSuccessFeature(
                        member.getUserId(), daily.getId(), method, today, evidence, now);
                // 확정됐으니 그날 좌표의 파기 타이머가 시작된다 — 고정 일괄 시각이 아니라 건별이다.
                locationPurge.scheduleFor(member.getUserId(), today, daily.getId(), now);
                // 즉시 확정된 성공은 확정 배치를 거치지 않는다 — finalizeDue 는 미확정 건만 집어가고
                // finalizeOne 은 초입에서 isTerminal() 로 되돌아간다. 그래서 성공 고지를 여기서
                // 하지 않으면 「성공한 날」만 알림이 없는 비대칭이 생긴다(실패는 확정 배치가 고지한다).
                // 같은 날을 여러 번 sync 해도 verification_id 멱등 키가 두 번째 적재를 막는다.
                notificationPublisher.publish(NotificationEvent.forChallenge(member.getUserId(),
                        NotificationType.VERIFICATION_RESULT,
                        member.getChallengeId(),
                        Map.of(NotificationParams.VARIANT, "SYNC_SUCCESS",
                                NotificationParams.VERIFICATION_ID, daily.getId().toString(),
                                NotificationParams.CHALLENGE_ID,
                                member.getChallengeId().toString())));
            }
        }
        return daily.getStatus();
    }

    /** 해당 method의 신호타입에 대해, 당일과 겹치는 비회복(recoverable=false) 권한 공백이 있는지(§8.5). */
    private boolean permissionGap(List<SyncRequest.Gap> gaps, VerificationMethod method, LocalDate day) {
        if (gaps == null || gaps.isEmpty()) return false;
        Set<String> types = MethodSignalTypes.of(method);
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

}
