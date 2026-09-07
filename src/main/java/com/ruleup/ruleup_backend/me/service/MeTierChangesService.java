package com.ruleup.ruleup_backend.me.service;

import com.ruleup.ruleup_backend.challenge.service.ChallengeTitleResolver;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.me.dto.MeTierChangesResponse;
import com.ruleup.ruleup_backend.me.dto.MeTierResponse;
import com.ruleup.ruleup_backend.score.ScoreTransactionRepository;
import com.ruleup.ruleup_backend.score.domain.ScoreTransaction;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 점수 변동 이력 전체 보기(GET /me/tier/changes).
 *
 * <p>원천은 {@code score_transactions} 하나이며 <b>조회만 얹는다</b> — 점수가 움직일 때마다
 * 행이 쌓이는 구조라 별도 적재가 필요 없다. 표기 규칙은 {@link MeTierService} 의 최근 변동과
 * 같아야 하므로 사유 매핑과 항목 조립을 그쪽과 공유한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MeTierChangesService {

    /** 페이지 크기는 서버 고정이다 — 클라이언트와 협상할 이유가 없다. */
    private static final int PAGE_SIZE = 50;

    /** 보관 1년 — 점수 및 티어 정책 §5. */
    private static final int RETENTION_DAYS = 365;

    private final ScoreTransactionRepository transactionRepository;
    private final ChallengeTitleResolver challengeTitles;
    private final ScoreChangeView changeView;

    public MeTierChangesResponse changes(UUID userId, String rawCursor) {
        Cursor cursor = decode(rawCursor);
        Instant since = Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS);

        // 한 건 더 읽어 다음 페이지 유무를 판단한다 — 총 건수를 세면 이력이 길수록 느려진다.
        List<ScoreTransaction> fetched = transactionRepository.findPage(
                userId, since,
                cursor == null ? null : cursor.createdAt(),
                cursor == null ? null : cursor.id(),
                PageRequest.of(0, PAGE_SIZE + 1));

        boolean hasNext = fetched.size() > PAGE_SIZE;
        List<ScoreTransaction> page = hasNext ? fetched.subList(0, PAGE_SIZE) : fetched;

        Map<UUID, String> titles = challengeTitles.titlesOf(
                page.stream().map(ScoreTransaction::getChallengeId).toList());
        List<MeTierResponse.Change> items = page.stream()
                .map(t -> changeView.toChange(t, titles))
                .toList();

        String next = hasNext && !page.isEmpty() ? encode(page.get(page.size() - 1)) : null;
        return new MeTierChangesResponse(items, next, RETENTION_DAYS);
    }

    // ===== 커서 =====

    /**
     * (변동 시각, 원장 id) 복합 커서. 시각만으로는 같은 밀리초에 쌓인 행이 페이지 경계에서
     * 새거나 겹친다 — 확정 배치가 여러 챌린지의 사이클 점수를 한 번에 쌓기 때문이다.
     */
    private record Cursor(Instant createdAt, UUID id) {}

    /**
     * 깨진 커서는 <b>조용히 첫 페이지로 떨어뜨리지 않는다.</b> 그렇게 하면 클라이언트가 무한
     * 스크롤 중 같은 페이지를 계속 다시 받아도 알아채지 못한다.
     */
    private Cursor decode(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8);
            int split = decoded.lastIndexOf('|');
            if (split <= 0) throw new IllegalArgumentException();
            return new Cursor(Instant.parse(decoded.substring(0, split)),
                    UUID.fromString(decoded.substring(split + 1)));
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.CURSOR_INVALID);
        }
    }

    private String encode(ScoreTransaction last) {
        String raw = last.getCreatedAt() + "|" + last.getId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
