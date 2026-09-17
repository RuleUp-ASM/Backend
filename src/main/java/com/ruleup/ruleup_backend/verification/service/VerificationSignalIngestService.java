package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.common.UuidGenerator;
import com.ruleup.ruleup_backend.verification.domain.SignalExclusionReason;
import com.ruleup.ruleup_backend.verification.evaluator.TimeWindows;
import com.ruleup.ruleup_backend.verification.signal.SignalDomain;
import com.ruleup.ruleup_backend.verification.signal.SyncSignal;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 신호 수신 — 원본 저장 + 영속 멱등 (백엔드 테크스펙 4-3 「신호 수신」, 4-1-1 저장 도메인).
 *
 * <h4>중복은 정상 경로다</h4>
 * 오프라인 복구, FCM 기동 후 일괄 전송, 구간 재전송이 전부 재전송을 만든다. 중복을 받아들이되
 * <b>판정에는 한 번만</b> 반영해야 하는데, 이 경계를 평가기의 메모리 상태나 evidence 에 맡기면
 * 평가기마다 다시 구현해야 하고 한 곳이라도 빠지면 사용 시간이 두 배가 된다. 그래서 수신
 * 지점에서 DB 유일성으로 끊는다.
 *
 * <h4>저장은 3개 도메인으로 갈라진다</h4>
 * 입력 타입 5종은 그대로 두고 저장만 {@link SignalDomain} 셋으로 접는다. 각 테이블은
 * {@code observedDate}(KST 귀속일) 기준 일별 파티션이라, <b>멱등 조회도 귀속일을 함께 건다</b> —
 * 유일 키가 {@code (observedDate, userId, dedupKey)} 라 날짜 없이 물으면 인덱스도 파티션
 * 프루닝도 못 쓴다.
 *
 * <p>멱등 키는 클라가 준 {@code recordId} 가 1순위, 없으면 신호 내용 전체의 해시다.
 * 내용 해시는 계약에 선언된 필드로만 만들어지므로, 필드가 늘어나면 예전 신호와 키가 달라질 수 있다 —
 * 그 경우 배포 직후 한 번 중복이 통과할 수 있고 그 이후로는 새 키로 안정화된다.
 */
@Service
@RequiredArgsConstructor
public class VerificationSignalIngestService {

    private static final Logger log = LoggerFactory.getLogger(VerificationSignalIngestService.class);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 한 번에 보관할 신호 수 상한(방어적). 바이트 상한은 요청 필터가 먼저 끊는다. */
    private static final int INSERT_BATCH = 500;

    private final JdbcTemplate jdbc;
    private final com.ruleup.ruleup_backend.verification.config.VerificationProperties properties;
    private final VerificationMetrics metrics;
    private final LocationPurgeService locationPurge;

    /**
     * 수신 결과.
     *
     * @param accepted     이번에 처음 받은 신호 — 판정 입력으로 넘긴다
     * @param droppedCount 중복으로 걸러낸 신호 수(sync_result 로깅 입력)
     */
    public record Ingested(List<SyncSignal> accepted, int droppedCount) {}

    /** 적재 대상 한 건 — 도메인·귀속일이 정해진 뒤의 모습. */
    private record Candidate(SignalDomain domain, LocalDate observedDate, String dedupKey,
                             SyncSignal signal, Instant occurredAt, SignalExclusionReason excludeReason,
                             boolean explicitRecordId) {}

    /**
     * 신호의 출처와 게이트 결정.
     *
     * @param deviceId 보낸 기기(없으면 null). 행에 적어 두어야 나중에 「어느 기기가 올린 신호인가」를 안다
     * @param gate     신호 타입 → 판정 배제 사유(없으면 null)
     */
    public record Source(String deviceId, java.util.function.Function<String, SignalExclusionReason> gate) {
        public static Source trusted() { return new Source(null, type -> null); }
    }

    /**
     * 신호를 원본 그대로 저장하고, 처음 받은 것만 골라 돌려준다.
     *
     * <p>같은 유저가 동시에 sync 를 치면 아주 드물게 같은 신호가 양쪽에서 "처음"으로 보일 수 있다.
     * 저장은 UNIQUE 가 막고(INSERT IGNORE), 판정 쪽은 평가기의 누적 워터마크가 한 번 더 막는다.
     */
    @Transactional
    public Ingested ingest(UUID userId, List<SyncSignal> signals, Instant receivedAt) {
        return ingest(userId, signals, receivedAt, Source.trusted());
    }

    /**
     * @param source 보낸 기기와 게이트 결정. 봉투 수준 게이트(VPN·무결성 실패·비활성 기기)의 결정을
     *               <b>행에 새긴다</b> — 요청 메모리에서만 빼면 다음 sync 의 전량 재평가가 되살린다
     */
    @Transactional
    public Ingested ingest(UUID userId, List<SyncSignal> signals, Instant receivedAt, Source source) {
        if (signals == null || signals.isEmpty()) return new Ingested(List.of(), 0);

        // 한 요청 안의 중복부터 접는다 — 같은 배치에 같은 신호가 두 번 실려 오는 일이 흔하다.
        Map<String, SyncSignal> byKey = new LinkedHashMap<>();
        int dropped = 0;
        for (SyncSignal s : signals) {
            if (s == null) continue;
            if (byKey.putIfAbsent(dedupKey(s), s) != null) dropped++;
        }
        if (byKey.isEmpty()) return new Ingested(List.of(), dropped);

        // 도메인 × 귀속일로 묶는다. 그 둘이 정해져야 어느 테이블의 어느 파티션을 볼지 정해진다.
        Map<SignalDomain, Map<LocalDate, List<Candidate>>> grouped = new LinkedHashMap<>();
        List<SyncSignal> unsupported = new ArrayList<>();
        for (Map.Entry<String, SyncSignal> e : byKey.entrySet()) {
            SyncSignal signal = e.getValue();
            Optional<SignalDomain> domain = SignalDomain.of(signal.type());
            if (domain.isEmpty()) { unsupported.add(signal); continue; }

            Instant occurredAt = TimeWindows.parseInstant(signal.observedAt());
            LocalDate observedDate = LocalDate.ofInstant(
                    (occurredAt != null) ? occurredAt : receivedAt, KST);
            grouped.computeIfAbsent(domain.get(), d -> new LinkedHashMap<>())
                    .computeIfAbsent(observedDate, d -> new ArrayList<>())
                    .add(new Candidate(domain.get(), observedDate, e.getKey(), signal, occurredAt,
                            source.gate().apply(signal.type()),
                            signal.recordId() != null && !signal.recordId().isBlank()));
        }
        if (!unsupported.isEmpty()) {
            // 평가기가 무시하는 타입이다. 계약에 없는 payload 를 쌓지 않는다(수집 최소화).
            log.info("unsupported_signal_dropped userId={} count={}", userId, unsupported.size());
            dropped += unsupported.size();
        }

        List<SyncSignal> accepted = new ArrayList<>();
        for (Map.Entry<SignalDomain, Map<LocalDate, List<Candidate>>> byDomain : grouped.entrySet()) {
            for (Map.Entry<LocalDate, List<Candidate>> byDate : byDomain.getValue().entrySet()) {
                dropped += store(userId, byDomain.getKey(), byDate.getKey(), byDate.getValue(),
                        receivedAt, source.deviceId(), accepted);
            }
        }
        return new Ingested(accepted, dropped);
    }

    /** 한 도메인·한 귀속일 묶음을 적재하고, 중복으로 걸러낸 수를 돌려준다. */
    private int store(UUID userId, SignalDomain domain, LocalDate observedDate,
                      List<Candidate> candidates, Instant receivedAt, String deviceId,
                      List<SyncSignal> accepted) {
        Set<String> keys = candidates.stream().map(Candidate::dedupKey)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> known = alreadyStored(userId, domain, observedDate, keys);
        Set<String> otherDate = storedOnOtherDate(userId, domain, observedDate,
                candidates.stream().filter(Candidate::explicitRecordId).map(Candidate::dedupKey)
                        .collect(java.util.stream.Collectors.toSet()));

        List<Object[]> rows = new ArrayList<>(candidates.size());
        List<String> resent = new ArrayList<>();
        int dropped = 0;
        for (Candidate c : candidates) {
            if (known.contains(c.dedupKey())) {
                dropped++;
                // 「다시 주장했다」로 쳐 주는 것은 <b>이번 요청이 게이트를 통과했을 때뿐</b>이다.
                // 못 믿을 봉투(비활성 기기·VPN·무결성 실패)가 보낸 재전송까지 수신 시각을 밀면,
                // 예전 기기가 recordId 만 알아도 새 기기의 판정을 움직인다 — 배제 사유를 행에
                // 새겨 둔 의미가 사라진다.
                if (c.excludeReason() == null) resent.add(c.dedupKey());
                continue;
            }
            if (otherDate.contains(c.dedupKey())) {
                // 같은 recordId 가 <b>다른 발생일</b>로 다시 왔다. 정상 재전송이 아니라 귀속일을 바꿔
                // 판정을 다시 받으려는 요청이거나 클라 버그다 — 파티션 유일 키가 (발생일, 유저,
                // dedupKey) 라 DB 는 이걸 막지 못하므로 여기서 거른다(백엔드 4-1-1).
                log.warn("signal_date_conflict userId={} domain={} observedDate={} dedupKey={}",
                        userId, domain, observedDate, c.dedupKey());
                dropped++;
                continue;
            }
            accepted.add(c.signal());
            rows.add(row(userId, c, receivedAt, deviceId, domain, observedDate));
        }
        insertAll(domain, rows);
        // <b>다시 보내온 것도 사건이다.</b> 본문은 이미 있으니 저장하지 않지만, 「언제 다시 주장했는가」는
        // 갱신한다. 수면처럼 <b>받은 때</b>를 기준으로 판정하는 신호가 있어서, 최초 수신 시각에 묶어 두면
        // 그때 유효하지 않았던 기록이 영영 되살아나지 못한다 — 아직 오지 않은 밤을 미리 올렸다가
        // 실제로 자고 난 뒤 정상 재전송해도 계속 제외됐다.
        //
        // <p>시간이 흐르는 것만으로는 이 값이 움직이지 않는다. 새 전송이 와야 한다 — 미리 올려두고
        // 기다리기만 하는 경로는 그대로 막힌다.
        refreshReceivedAt(userId, domain, observedDate, resent, receivedAt);
        metrics.signalsStored(rows.size());
        return dropped;
    }

    /** 이미 저장된 원본의 수신 시각을 <b>앞으로만</b> 민다. 되돌아가는 갱신은 하지 않는다. */
    private void refreshReceivedAt(UUID userId, SignalDomain domain, LocalDate observedDate,
                                   List<String> dedupKeys, Instant receivedAt) {
        if (dedupKeys.isEmpty()) return;
        for (int from = 0; from < dedupKeys.size(); from += INSERT_BATCH) {
            List<String> chunk = dedupKeys.subList(from, Math.min(from + INSERT_BATCH, dedupKeys.size()));
            String placeholders = String.join(",", Collections.nCopies(chunk.size(), "?"));
            List<Object> args = new ArrayList<>();
            args.add(Timestamp.from(receivedAt));
            args.add(Date.valueOf(observedDate));
            args.add(bytes(userId));
            args.addAll(chunk);
            args.add(Timestamp.from(receivedAt));
            jdbc.update("UPDATE " + domain.table() + " SET receivedAt = ?"
                            + " WHERE observedDate = ? AND userId = ? AND dedupKey IN (" + placeholders + ")"
                            + " AND receivedAt < ?",
                    args.toArray());
        }
    }

    /**
     * 같은 dedupKey 가 <b>다른 귀속일</b>로 이미 저장돼 있는지. 클라가 recordId 를 명시한 신호만 본다 —
     * 내용 해시로 만든 키는 날짜가 내용에 들어 있어 애초에 충돌하지 않는다.
     *
     * <p>검사 창은 <b>원본 보관 기간</b>이다. 앞뒤 하루만 보면 그 밖의 날짜에 같은 레코드가
     * 남아 있어도 통과하는데, 보관 중인 원본은 전부 판정 재평가의 입력이라 「닿지 못한다」고
     * 말할 수 없다. 어차피 파티션 프루닝이 걸리는 조회라 창을 넓혀도 비용은 거의 같다.
     */
    private Set<String> storedOnOtherDate(UUID userId, SignalDomain domain, LocalDate observedDate,
                                          Set<String> keys) {
        if (keys.isEmpty()) return Set.of();
        Set<String> found = new HashSet<>();
        List<String> all = new ArrayList<>(keys);
        for (int from = 0; from < all.size(); from += INSERT_BATCH) {
            List<String> chunk = all.subList(from, Math.min(from + INSERT_BATCH, all.size()));
            String placeholders = String.join(",", Collections.nCopies(chunk.size(), "?"));
            int window = properties.signalRetentionDays();
            List<Object> args = new ArrayList<>();
            args.add(Date.valueOf(observedDate.minusDays(window)));
            args.add(Date.valueOf(observedDate.plusDays(window)));
            args.add(Date.valueOf(observedDate));
            args.add(bytes(userId));
            args.addAll(chunk);
            found.addAll(jdbc.queryForList(
                    "SELECT dedupKey FROM " + domain.table()
                            + " WHERE observedDate BETWEEN ? AND ? AND observedDate <> ? AND userId = ?"
                            + " AND dedupKey IN (" + placeholders + ")",
                    String.class, args.toArray()));
        }
        return found;
    }

    /**
     * 이미 저장된 키. <b>귀속일을 함께 건다</b> — 유일 키가 {@code (observedDate, userId, dedupKey)}
     * 라 날짜가 빠지면 인덱스를 못 타고 모든 파티션을 뒤진다.
     */
    private Set<String> alreadyStored(UUID userId, SignalDomain domain, LocalDate observedDate,
                                      Set<String> keys) {
        Set<String> found = new HashSet<>();
        List<String> all = new ArrayList<>(keys);
        for (int from = 0; from < all.size(); from += INSERT_BATCH) {
            List<String> chunk = all.subList(from, Math.min(from + INSERT_BATCH, all.size()));
            String placeholders = String.join(",", Collections.nCopies(chunk.size(), "?"));
            List<Object> args = new ArrayList<>();
            args.add(Date.valueOf(observedDate));
            args.add(bytes(userId));
            args.addAll(chunk);
            found.addAll(jdbc.queryForList(
                    "SELECT dedupKey FROM " + domain.table()
                            + " WHERE observedDate = ? AND userId = ? AND dedupKey IN (" + placeholders + ")",
                    String.class, args.toArray()));
        }
        return found;
    }

    /**
     * INSERT IGNORE 로 적재한다 — 동시 요청이 같은 키를 넣어도 예외 없이 한 건만 남는다.
     * 예외로 처리하면 트랜잭션이 롤백 표시돼 나머지 신호까지 잃는다.
     */
    /**
     * 적재. 위치 도메인만 컬럼이 하나 더 붙는다 — <b>파기 예정 시각</b>이다.
     *
     * <p>확정 때 거는 대신 여기서 미리 박는 이유는 두 가지다. 첫째, 시각이 <b>귀속일만으로</b>
     * 정해져 미리 알 수 있다. 둘째, 인증에 한 번도 쓰이지 않은 좌표(위치 챌린지가 없는 사용자의
     * 신호)도 파기 대상이 된다 — 확정 때만 걸면 그런 행은 타이머 없이 남아 파티션이 걷어갈 때까지
     * 파기 기록조차 생기지 않는다.
     */
    private void insertAll(SignalDomain domain, List<Object[]> rows) {
        boolean location = (domain == SignalDomain.LOCATION);
        String sql = "INSERT IGNORE INTO " + domain.table()
                + " (id, observedDate, userId, deviceId, signalType, excludeReason, occurredAt,"
                + "  receivedAt, payload, dedupKey" + (location ? ", purgeAfter)" : ")")
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?" + (location ? ", ?)" : ")");
        for (int from = 0; from < rows.size(); from += INSERT_BATCH) {
            List<Object[]> chunk = rows.subList(from, Math.min(from + INSERT_BATCH, rows.size()));
            jdbc.batchUpdate(sql, chunk);
        }
    }

    private Object[] row(UUID userId, Candidate c, Instant receivedAt, String deviceId,
                         SignalDomain domain, LocalDate observedDate) {
        Object[] base = {
                bytes(UuidGenerator.generate()),
                Date.valueOf(c.observedDate()),
                bytes(userId),
                deviceId,
                (c.signal().type() != null) ? c.signal().type() : "UNKNOWN",
                (c.excludeReason() != null) ? c.excludeReason().name() : null,
                (c.occurredAt() != null) ? Timestamp.from(c.occurredAt()) : null,
                Timestamp.from(receivedAt),
                JSON.writeValueAsString(c.signal()),
                c.dedupKey()};
        if (domain != SignalDomain.LOCATION) return base;

        Object[] withPurge = java.util.Arrays.copyOf(base, base.length + 1);
        withPurge[base.length] = Timestamp.from(locationPurge.purgeAfterFor(observedDate));
        return withPurge;
    }

    /** recordId 가 있으면 그것으로, 없으면 신호 내용 전체로 만든 해시. */
    private String dedupKey(SyncSignal signal) {
        String source = (signal.recordId() != null && !signal.recordId().isBlank())
                ? "rec:" + signal.recordId().trim()
                : "sig:" + JSON.writeValueAsString(signal);
        return sha256Hex(source);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 미지원", e);   // 표준 JDK 에서는 발생하지 않는다
        }
    }

    private static byte[] bytes(UUID id) {
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(id.getMostSignificantBits());
        bb.putLong(id.getLeastSignificantBits());
        return bb.array();
    }
}
