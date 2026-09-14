package com.ruleup.ruleup_backend.verification.service;
import com.ruleup.ruleup_backend.common.verification.*;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.service.ChallengeQueryService;
import com.ruleup.ruleup_backend.challenge.stats.ChallengeStatsRefreshRequested;
import com.ruleup.ruleup_backend.verification.domain.*;
import com.ruleup.ruleup_backend.verification.evaluator.DayContext;
import com.ruleup.ruleup_backend.verification.evaluator.EvaluationOutcome;
import com.ruleup.ruleup_backend.verification.evaluator.MethodEvaluator;
import com.ruleup.ruleup_backend.verification.signal.DaySignals;
import com.ruleup.ruleup_backend.verification.signal.SyncSignal;
import com.ruleup.ruleup_backend.common.verification.GeoAnchor;
import com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository;
import com.ruleup.ruleup_backend.verification.repository.VerificationFailureDetailRepository;
import com.ruleup.ruleup_backend.verification.repository.VerificationMethodResultRepository;
import com.ruleup.ruleup_backend.common.outbox.OutboxDispatcher;
import com.ruleup.ruleup_backend.common.outbox.OutboxService;
import com.ruleup.ruleup_backend.watcher.service.WatcherFailureOutboxHandler;
import com.ruleup.ruleup_backend.notification.NotificationEvent;
import com.ruleup.ruleup_backend.notification.NotificationPublisher;
import com.ruleup.ruleup_backend.notification.domain.NotificationParams;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 인증 확정 배치 (인증 정책 §2 · 테크스펙 §4-3 "일일 확정 배치"). 두 작업:
 *  1) finalizeDue : <b>귀속일 이틀 뒤 00:00 KST</b>가 지난 미확정 행을 최종 재평가해 완료·실패로 확정한다.
 *     재평가 입력은 그 귀속일의 <b>원본 신호 전량</b>이다 — 저장된 실패 사유를 그대로 믿으면
 *     유예 하루 동안 늦게 도착한 신호가 반영되지 않는다(백엔드 4-3 「대상 건의 최신 유효 신호를 다시 조회함」).
 *     - 목표 달성형: 성공은 이미 즉시 확정됐으므로 여기 남은 건 미달 → 실패.
 *     - 규칙 지키기형: 위반이 남아 있으면 실패, 없으면 완료.
 *     이 시각 전에는 어떤 실패도 확정되지 않는다 — 늦게 도착하는 신호로 뒤집힐 수 있기 때문이다.
 *  2) rolloverFrequencyPeriods : 빈도형 주기 종료분을 미달 정산 + 다음 주기로 롤오버.
 *
 * <p>1분 주기로 도는 폴러지만 대상 조건이 {@code finalizeAfter <= now} 라, 실제 확정은 각 귀속일의
 * 이틀 뒤 00:00 KST 에만 일어난다. 귀속일 종료 후 하루는 늦게 도착하는 신호를 받는 유예 구간이고,
 * 유저는 그 사이 "이대로면 실패"를 보고 이의를 낸다. 폴러라서 배포·장애로 배치가 밀려도 스스로 따라잡고(catch-up),
 * 이미 확정된 건은 건너뛰므로 재실행이 안전하다.
 * FOR UPDATE SKIP LOCKED 선점이라 다중 인스턴스에서도 같은 대상을 중복 처리하지 않는다.
 */
@Service
public class VerificationFinalizeService {

    private static final Logger log = LoggerFactory.getLogger(VerificationFinalizeService.class);

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /**
     * 한 트랜잭션에 담는 대상 수. 크게 잡을수록 커밋 횟수가 줄지만 그만큼 행 잠금을 오래 쥐고,
     * 한 건이 실패했을 때 되돌아가는 범위도 커진다.
     */
    private static final int CLAIM_LIMIT = 500;

    /**
     * 한 번 깨워 비우는 데 쓸 시간 예산. tick 주기(60초)보다 짧게 둬서 다음 폴링과 겹치지 않게 한다 —
     * 넘기면 남은 대상은 다음 tick 이 이어 집는다(FOR UPDATE SKIP LOCKED 라 중복 처리도 없다).
     */
    private static final java.time.Duration DRAIN_BUDGET = java.time.Duration.ofSeconds(45);
    /** 한 번에 채울 무신호 대상 상한. 유저 2만 × 동시 3개 기준 일 6만 건이라 여유를 둔다. */
    private static final int MATERIALIZE_LIMIT = 100_000;

    private final VerificationDailyRepository dailyRepo;
    private final VerificationMethodResultRepository methodResultRepo;
    private final VerificationFailureDetailRepository failureDetailRepo;
    private final SignalExclusionRecorder exclusionRecorder;
    private final ChallengeQueryService challengeQuery;
    private final VerificationConfigFactory configFactory;
    private final VerificationProgressService progressService;
    private final NotificationPublisher notificationPublisher;
    private final ApplicationEventPublisher eventPublisher;
    private final VerificationSignalReader signalReader;
    private final MemberSettingsResolver settingsResolver;
    private final AnomalyEventRecorder anomalyRecorder;
    private final LocationPurgeService locationPurge;
    private final OutboxService outbox;
    private final OutboxDispatcher outboxDispatcher;
    private final VerificationMetrics metrics;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;
    private final Map<VerificationMethod, MethodEvaluator> evaluators;

    public VerificationFinalizeService(VerificationDailyRepository dailyRepo,
                                       VerificationMethodResultRepository methodResultRepo,
                                       VerificationFailureDetailRepository failureDetailRepo,
                                       SignalExclusionRecorder exclusionRecorder,
                                       ChallengeQueryService challengeQuery,
                                       VerificationConfigFactory configFactory,
                                       VerificationProgressService progressService,
                                       NotificationPublisher notificationPublisher,
                                       ApplicationEventPublisher eventPublisher,
                                       VerificationSignalReader signalReader,
                                       MemberSettingsResolver settingsResolver,
                                       AnomalyEventRecorder anomalyRecorder,
                                       LocationPurgeService locationPurge,
                                       OutboxService outbox,
                                       OutboxDispatcher outboxDispatcher,
                                       VerificationMetrics metrics,
                                       org.springframework.transaction.support.TransactionTemplate transactionTemplate,
                                       List<MethodEvaluator> evaluatorList) {
        this.dailyRepo = dailyRepo;
        this.methodResultRepo = methodResultRepo;
        this.failureDetailRepo = failureDetailRepo;
        this.exclusionRecorder = exclusionRecorder;
        this.challengeQuery = challengeQuery;
        this.configFactory = configFactory;
        this.progressService = progressService;
        this.notificationPublisher = notificationPublisher;
        this.eventPublisher = eventPublisher;
        this.signalReader = signalReader;
        this.settingsResolver = settingsResolver;
        this.anomalyRecorder = anomalyRecorder;
        this.locationPurge = locationPurge;
        this.outbox = outbox;
        this.outboxDispatcher = outboxDispatcher;
        this.metrics = metrics;
        this.transactionTemplate = transactionTemplate;
        this.evaluators = evaluatorList.stream().collect(
                java.util.stream.Collectors.toMap(MethodEvaluator::method, e -> e, (a, b) -> a));
    }

    /**
     * 매일 00:00:30 KST: 확정 시각이 막 지난 귀속일(D-2)에 대해 <b>행이 없는 대상</b>을 채운다.
     *
     * <p>판정 행은 sync 가 만든다. 그래서 그날 앱을 한 번도 켜지 않은 사용자는 행이 아예 없고,
     * 확정 배치가 "PENDING 행"만 훑으면 그 날짜는 실패로도 확정되지 않아 통계에서 통째로 사라진다.
     * 여기서 빈 자리를 채워 두면 아래 {@link #finalizeDue()} 가 NO_SIGNAL_RECEIVED 로 확정한다.
     *
     * <p>대상 아닌 날(요일 밖·기간 밖·빈도 몫 충족)은 열지 않는다 — 확정되지 않을 행을 만들 이유가 없다.
     * 재실행해도 이미 있는 행은 건너뛰므로 안전하다.
     */
    @Scheduled(cron = "30 0 0 * * *", zone = "Asia/Seoul")
    @Transactional
    public void materializeDueTargets() {
        LocalDate targetDate = LocalDate.now(KST).minusDays(2);   // 확정 시각이 방금 지난 귀속일
        List<ChallengeMember> members = challengeQuery.findActiveOnDate(targetDate, MATERIALIZE_LIMIT);
        int opened = 0;
        for (ChallengeMember member : members) {
            if (dailyRepo.findByChallengeMemberIdAndTargetDate(member.getId(), targetDate).isPresent()) continue;
            Challenge challenge = challengeQuery.findChallenge(member.getChallengeId()).orElse(null);
            if (challenge == null) continue;
            VerificationConfig config = configFactory.build(challenge);
            if (config.isManual()) continue;   // 수동 인증은 미체크가 곧 미수행 — 자동 확정 대상이 아니다
            if (VerificationTargetDays.of(config, challenge, member, targetDate)
                    != VerificationTargetDays.Disposition.EVALUATE) {
                continue;
            }
            VerificationDaily daily = dailyRepo.save(VerificationDaily.open(
                    member.getId(), challenge.getId(), member.getUserId(), targetDate));
            daily.applyWindow(null);
            opened++;
        }
        if (opened > 0) log.info("무신호 귀속일 채우기: {} 대상 {}건 개시", targetDate, opened);
    }

    /**
     * 1분마다 폴링하되, 실제 확정은 귀속일 이틀 뒤 00:00 KST 가 지난 건에서만 일어난다.
     *
     * <h4>한 번 깨울 때 <b>끝까지</b> 비운다</h4>
     * 예전에는 한 tick 에 청크 하나(200건)만 집었다. 일 6만 건 기준으로 이론상 다섯 시간이 걸려
     * 03:30 탐색 reconciliation 전에 끝나지 못한다 — 그 배치가 확정 결과를 입력으로 쓰므로
     * 완주율·유지율이 하루씩 밀린 값으로 계산된다. 그래서 대상이 남아 있는 동안 청크를 이어
     * 돌리고, 시간 예산을 넘기면 다음 tick 에 넘긴다(폴러라 catch-up 은 그대로 유지된다).
     *
     * <h4>한 건이 전체를 되돌리지 않는다</h4>
     * 트랜잭션은 <b>청크 단위</b>다. 청크가 통째로 실패하면 그 청크만 건별 트랜잭션으로 다시
     * 돌려 문제 있는 한 건만 남기고 나머지를 통과시킨다 — 스펙의 「한 건의 판정 실패 때문에
     * 전체 일 배치가 롤백되지 않도록」이 이 모양이다.
     */
    @Scheduled(fixedDelay = 60_000)
    public void finalizeDue() {
        long startedAt = System.nanoTime();
        Instant deadline = Instant.now().plus(DRAIN_BUDGET);
        int total = 0;
        while (Instant.now().isBefore(deadline)) {
            int done = finalizeChunkSafely();
            total += done;
            if (done < CLAIM_LIMIT) break;   // 대상이 바닥났다
        }
        if (total > 0) {
            // 적재한 감시자 통지를 곧바로 흘린다. 실패해도 스윕이 다시 집는다.
            outboxDispatcher.requestFlush();
            metrics.finalizeBatch(total, System.nanoTime() - startedAt);
            log.info("인증 확정 배치: 귀속일이 끝난 미확정 {}건 확정 처리", total);
        }
    }

    /** 청크 하나. 통째로 실패하면 건별로 다시 돌려 나머지를 살린다. */
    private int finalizeChunkSafely() {
        try {
            return finalizeChunk(CLAIM_LIMIT);
        } catch (RuntimeException e) {
            log.warn("확정 청크 실패 — 건별로 다시 돌린다. err={}", e.toString());
            return finalizeChunk(1);
        }
    }

    /**
     * @param limit 한 트랜잭션에 담을 대상 수. 1 이면 건별 격리 모드다
     * @return 이번에 집은 대상 수(확정 성공 여부와 무관 — 0 이면 더 볼 것이 없다)
     */
    private int finalizeChunk(int limit) {
        Integer claimed = transactionTemplate.execute(tx -> {
            Instant now = Instant.now();
            List<VerificationDaily> due = dailyRepo.findDuePendingForUpdate(now, limit);
            Set<UUID> changedChallenges = new HashSet<>();
            for (VerificationDaily daily : due) {
                if (finalizeOne(daily, now)) changedChallenges.add(daily.getChallengeId());
            }
            changedChallenges.forEach(challengeId -> eventPublisher.publishEvent(
                    ChallengeStatsRefreshRequested.of(challengeId, "VERIFICATION_FINALIZED")));
            return due.size();
        });
        return (claimed != null) ? claimed : 0;
    }

    /**
     * 한 건 최종 재평가·확정. 확정 결과가 이미 있으면 건너뛴다(재실행 멱등).
     *
     * <p><b>저장된 실패 사유를 그대로 믿지 않고 원본으로 다시 판정한다.</b> 유예 하루 동안 늦게
     * 도착한 신호가 sync 를 거치지 않고 쌓여 있을 수 있고(그 멤버가 그 사이 한 번도 평가되지
     * 않은 경우), 그러면 확정 시각의 진실은 저장된 요약이 아니라 원본에 있다.
     */
    private boolean finalizeOne(VerificationDaily daily, Instant now) {
        if (daily.isTerminal()) return false;   // 다른 인스턴스가 먼저 확정 — 중복 확정 금지

        Challenge challenge = challengeQuery.findChallenge(daily.getChallengeId()).orElse(null);
        if (challenge == null) {
            // 방이 사라졌으면 판정 기준도 함께 사라졌다. 여기서 실패로 확정하면 「신호를 못 받아서
            // 실패」라는 <b>사실이 아닌 사유</b>가 기록에 남는다 — 실제로는 판정할 설정이 없었을
            // 뿐이다. 대상이 아니었던 것으로 닫아 폴링에서 빼고 실패 통계에도 넣지 않는다.
            // (자동 삭제 배치가 마지막 활동일의 확정 이후에만 방을 지우므로 정상 경로에서는
            //  여기에 오지 않는다 — 수동 삭제·데이터 정합 사고의 방어선이다.)
            log.warn("확정 대상의 챌린지가 없다 — 대상 아님으로 닫는다. verificationId={} challengeId={}",
                    daily.getId(), daily.getChallengeId());
            daily.recordResult(VerificationStatus.NOT_TARGET, daily.getMethod(), null, null);
            return false;
        }
        VerificationConfig config = configFactory.build(challenge);
        VerificationMethod method = config.primaryMethod();
        Polarity polarity = VerificationPolarity.of(config);
        ChallengeMember member = challengeQuery.findMember(daily.getChallengeMemberId()).orElse(null);

        // 원본으로 최종 재평가. 평가기가 없거나 멤버가 사라졌으면 저장된 요약으로 물러선다.
        EvaluationOutcome outcome = reevaluate(daily, config, method, member, now);
        Map<String, Object> evidence = (outcome != null) ? outcome.evidence() : evidenceOf(daily, method);
        String failureReason = (outcome != null) ? outcome.failureReason() : daily.getFailureReason();
        boolean succeeded = (outcome != null) && outcome.status() == VerificationStatus.SUCCESS;

        boolean confirmedFail;
        if (succeeded || (polarity == Polarity.CONSTRAINT && failureReason == null)) {
            // 성공 조건을 채웠거나, 정해진 기간 동안 유효한 위반이 없었다 → 완료 확정.
            daily.recordResult(VerificationStatus.SUCCESS, method.name(), null, now);
            confirmedFail = false;
        } else {
            String reasonCode = finalFailureReason(failureReason, evidence, method, config);
            daily.confirmFailure(now, method.name(), reasonCode);
            // 실패 상세는 **확정된 실패에만** 남긴다. 실패 예정은 뒤집힐 수 있는 계산 상태라
            // 행을 만들면 이의로 완료가 된 뒤에도 「실패했다는 기록」이 남는다.
            recordFailureDetail(daily, reasonCode, evidence, now);
            confirmedFail = true;
        }

        // 확정 시점에 판정에서 뺀 신호를 배제 로그로 옮긴다 — 성공·실패를 가리지 않는다.
        // 신호 위생 이상은 인증 결과와 무관하게 탐지 입력으로 남겨야 한다(공통 3절 ①).
        exclusionRecorder.recordEvaluationHygiene(daily.getUserId(), daily.getId(), method, evidence, now);
        if (!confirmedFail) {
            // 성공 인증만 탐지 feature 로 승격한다 — 실패 인증은 anomaly 데이터셋을 만들지 않는다.
            anomalyRecorder.recordSuccessFeature(daily.getUserId(), daily.getId(), method,
                    daily.getTargetDate(), evidence, now);
        }
        // 성공이든 실패든 확정은 확정이다. 좌표 파기 타이머는 여기서 시작된다(공통 5-6).
        locationPurge.scheduleFor(daily.getUserId(), daily.getTargetDate(), daily.getId(), now);

        refreshProgress(member, daily);

        // 확정된 실패만 감시자 통지 적재. 실패 예정 단계에서는 통지하지 않는다(확정이 아니므로).
        // 아웃박스라 확정과 같은 커밋에 들어가고, 통지가 한 번 실패해도 스윕이 다시 집는다 —
        // 인메모리 이벤트로 내면 그 실패는 감시자에게 영원히 가지 않는다.
        if (confirmedFail && member != null) {
            outbox.enqueue(WatcherFailureOutboxHandler.OUTBOX_TYPE,
                    new WatcherFailureOutboxHandler.Payload(
                            daily.getChallengeId().toString(), member.getUserId().toString(),
                            daily.getId().toString(), daily.getTargetDate().toString(), now.toString()),
                    WatcherFailureOutboxHandler.OUTBOX_TYPE + ":" + daily.getId());
        }

        // 판정 결과 고지 — 성공·실패 둘 다. 확정 시각이 귀속일 이틀 뒤 00:00 이라 그때 유저는
        // 앱을 보고 있지 않다. 알림함이 「그날이 어떻게 끝났는지」를 확인할 유일한 자리다.
        // 판정 하나에 확정은 하나뿐이라 verification_id 가 곧 멱등 키이고, 배치가 재실행돼도
        // 두 번 적재되지 않는다.
        if (member != null) {
            notificationPublisher.publish(NotificationEvent.forChallenge(member.getUserId(),
                    NotificationType.VERIFICATION_RESULT,
                    daily.getChallengeId(),
                    Map.of(NotificationParams.VARIANT,
                                    confirmedFail ? "CONFIRMED_FAILURE" : "CONFIRMED_SUCCESS",
                            NotificationParams.VERIFICATION_ID, daily.getId().toString(),
                            NotificationParams.CHALLENGE_ID, daily.getChallengeId().toString())));
        }
        return member != null;
    }

    /**
     * 그 귀속일의 원본으로 최종 재평가한다. 평가기가 없거나(새 방식을 enum 에만 추가한 경우)
     * 멤버가 사라졌으면 null 을 돌려 저장된 요약으로 물러선다.
     *
     * <p>설정은 <b>그 날 적용되던 값</b>을 쓴다 — 유예 구간에 장소·대상 앱을 바꿔도 지난 판정이
     * 흔들리면 안 된다.
     */
    private EvaluationOutcome reevaluate(VerificationDaily daily, VerificationConfig config,
                                         VerificationMethod method, ChallengeMember member, Instant now) {
        MethodEvaluator evaluator = evaluators.get(method);
        if (evaluator == null || member == null) return null;

        LocalDate targetDate = daily.getTargetDate();
        List<SyncSignal> ofDay = DaySignals.forDate(
                signalReader.forDay(daily.getUserId(), targetDate), targetDate, KST);

        VerificationMethodResult mr = methodResultRepo
                .findByVerificationDailyIdAndMethod(daily.getId(), method.name())
                .orElseGet(() -> VerificationMethodResult.create(
                        daily.getId(), method.name(), VerificationPolarity.of(config), true));
        Map<String, Object> stored = mr.getEvidence();

        if (!MethodSignalTypes.anyFor(method, ofDay)) {
            // 그 방식이 읽을 신호가 하나도 없다. 평가기를 돌리면 「진행도 0」 근거가 만들어지고
            // 확정 사유가 무신호 대신 목표 미달로 바뀐다 — 유저에게 할 안내가 달라진다.
            // 다만 원본 보관 기간이 지나 비어 보이는 경우가 있어, 저장된 근거가 있으면 그쪽을 믿는다.
            if (stored != null && !stored.isEmpty()) return null;
            return new EvaluationOutcome(VerificationStatus.PENDING, null,
                    carryPendingReason(null, stored), null);
        }

        List<String> screenApps = settingsResolver.screenAppPackagesOn(member, targetDate);
        List<GeoAnchor> anchors = settingsResolver.anchorsOn(member, targetDate);
        EvaluationOutcome outcome = evaluator.evaluate(new DayContext(
                targetDate, KST, now, config, ofDay, anchors, screenApps, member.getId().toString()));

        // 재평가 결과를 방식 행에 남긴다 — 실패 상세와 배제 로그가 이 근거를 읽는다.
        Map<String, Object> evidence = carryPendingReason(outcome.evidence(), stored);
        mr.evaluate(outcome.status(), evidence, now);
        methodResultRepo.save(mr);
        return new EvaluationOutcome(outcome.status(), outcome.failureReason(), evidence,
                outcome.windowClosesAt());
    }

    /**
     * 권한 공백 힌트는 신호가 아니라 <b>봉투</b>에서 온다(gaps). 원본 재평가는 그것을 알 수 없으므로
     * sync 가 남겨 둔 값을 이어 붙인다 — 놓치면 「권한 없음」이 「신호 없음」으로 뭉개진다.
     */
    private Map<String, Object> carryPendingReason(Map<String, Object> fresh, Map<String, Object> stored) {
        String carried = FailureReasons.pendingReasonOf(stored);
        if (carried == null) return fresh;
        Map<String, Object> merged = (fresh != null) ? new java.util.HashMap<>(fresh) : new java.util.HashMap<>();
        merged.putIfAbsent("pendingReason", carried);
        return merged;
    }

    /**
     * 최종 실패 사유. 재평가가 낸 "실패 예정" 사유가 있으면 그대로 쓰고,
     * 없으면 신호 자체가 없었던 경우(권한 공백 / 신뢰 게이트 탈락 / 무신호)를 구분해 붙인다.
     */
    private String finalFailureReason(String failureReason, Map<String, Object> evidence,
                                      VerificationMethod method, VerificationConfig config) {
        if (failureReason != null) return failureReason;
        if (evidence == null || evidence.isEmpty()) return "NO_SIGNAL_RECEIVED";

        String pendingReason = FailureReasons.pendingReasonOf(evidence);
        if ("UNTRUSTED_HEALTH_SOURCE".equals(pendingReason)) return "UNTRUSTED_HEALTH_SOURCE";
        if ("PERMISSION_MISSING".equals(pendingReason)) return "PERMISSION_MISSING";   // 무신호와 구분
        return FailureReasons.of(method, config);
    }

    /**
     * 실패 상세 기록 — <b>왜 실패했는지를 설명할 수 있게</b> 사유 코드·요약·기준값·실제값을 남긴다
     * (개인정보보호법의 자동화된 결정 설명, 공통 5-8).
     *
     * <p>기준값을 판정 시점 값으로 <b>스냅샷</b>해 두는 것이 핵심이다. GPS 반경·기상 허용 범위는
     * 배포 없이 조정하는 값이라, 나중 기준으로 과거 판정을 설명하면 틀린 설명이 된다.
     *
     * <p>PK 가 판정 id 라 배치가 재실행돼도 같은 행을 덮어쓴다. 실패가 이의로 뒤집히는 경로는
     * 없다 — 이의 기한이 확정 시각과 같아서, 확정된 실패는 이미 신청 창이 닫혀 있다.
     */
    private void recordFailureDetail(VerificationDaily daily, String reasonCode,
                                     Map<String, Object> evidence, Instant now) {
        failureDetailRepo.save(VerificationFailureDetail.of(
                daily.getId(), reasonCode, FailureEvidence.of(reasonCode, evidence), now));
    }

    /** 그 판정에 쌓인 평가 근거. 방식이 없으면(챌린지가 사라진 경우) 읽을 것도 없다. */
    private Map<String, Object> evidenceOf(VerificationDaily daily, VerificationMethod method) {
        if (method == null) return null;
        return methodResultRepo.findByVerificationDailyIdAndMethod(daily.getId(), method.name())
                .map(VerificationMethodResult::getEvidence).orElse(null);
    }

    /** 진행률 재계산 + (그날이 오늘이면) todayStatus 뱃지 캐시 갱신. */
    private void refreshProgress(ChallengeMember member, VerificationDaily daily) {
        if (member == null) return;
        if (daily.getTargetDate().equals(LocalDate.now(KST))) {
            progressService.recountAndSetToday(member, daily.getStatus());
        } else {
            progressService.recount(member);
        }
    }

    /** 매일 00:05 KST: 종료된 빈도형 주기 정산 + 롤오버. */
    @Scheduled(cron = "0 5 0 * * *", zone = "Asia/Seoul")
    @Transactional
    public void rolloverFrequencyPeriods() {
        LocalDate today = LocalDate.now(KST);
        List<ChallengeMember> members = challengeQuery.findFrequencyRolloverTargets(today);
        Set<UUID> changedChallenges = new HashSet<>();
        for (ChallengeMember m : members) {
            Challenge ch = challengeQuery.findActiveChallenge(m.getChallengeId()).orElse(null);
            if (ch == null) continue;
            if (rolloverMember(m, ch, today)) {
                progressService.recount(m);
                changedChallenges.add(ch.getId());
            }
        }
        changedChallenges.forEach(challengeId -> eventPublisher.publishEvent(
                ChallengeStatsRefreshRequested.of(challengeId, "FREQUENCY_ROLLOVER")));
        if (!members.isEmpty()) {
            log.info("빈도형 주기 롤오버: 대상 {}건 정산", members.size());
        }
    }

    /** 종료된 주기를 따라잡으며 정산(다운타임으로 여러 주기가 밀렸어도 catch-up). */
    private boolean rolloverMember(ChallengeMember m, Challenge ch, LocalDate today) {
        int guard = 0;
        boolean changed = false;
        while (m.getCurPeriodEnd() != null && m.getCurPeriodEnd().isBefore(today) && guard++ < 400) {
            int need = (m.getPeriodTarget() != null) ? m.getPeriodTarget() : 0;
            int done = (m.getCurPeriodCompleted() != null) ? m.getCurPeriodCompleted() : 0;
            int shortfall = Math.max(need - done, 0);

            LocalDate nextStart = m.getCurPeriodEnd().plusDays(1);
            if (nextStart.isAfter(ch.getEndDate())) {
                // 챌린지 종료: 마지막 주기 미달만 정산하고 advance 안 함(루프 종료)
                m.rolloverPeriod(m.getCurPeriodStart(), m.getCurPeriodEnd(), shortfall);
                changed = true;
                break;
            }
            int periodDays = (m.getPeriodUnit() == PeriodUnit.WEEK) ? 7 : 30;
            LocalDate nextEnd = nextStart.plusDays(periodDays - 1L);
            if (nextEnd.isAfter(ch.getEndDate())) nextEnd = ch.getEndDate();
            m.rolloverPeriod(nextStart, nextEnd, shortfall);
            changed = true;
        }
        return changed;
    }


}
