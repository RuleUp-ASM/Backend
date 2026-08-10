package com.ruleup.ruleup_backend.room.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeStatus;
import com.ruleup.ruleup_backend.challenge.domain.MemberStatus;
import com.ruleup.ruleup_backend.challenge.domain.ParticipationType;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.room.dto.CrossRankingDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CrossRankingService {
    private final ChallengeRepository challengeRepository;
    private final ChallengeMemberRepository memberRepository;
    private record Row(Challenge challenge, int success, int total, BigDecimal rate) {}

    public CrossRankingDtos.Response get(String rawMode, UUID challengeId, String cursor, Integer requestedSize) {
        ParticipationType mode;
        try { mode = ParticipationType.valueOf(rawMode == null ? "" : rawMode); }
        catch (IllegalArgumentException e) { throw new BusinessException(ErrorCode.INVALID_RANKING_MODE); }
        int minimum = mode == ParticipationType.GROUP ? 50 : 10;
        List<Row> rows = new ArrayList<>();
        for (Challenge challenge : challengeRepository.findAll()) {
            if (challenge.getDeletedAt() != null || challenge.getStatus() != ChallengeStatus.ACTIVE
                    || challenge.getParticipationType() != mode) continue;
            if (mode == ParticipationType.SOLO && Boolean.FALSE.equals(challenge.getRankingVisible())) continue;
            List<ChallengeMember> members = memberRepository
                    .findByChallengeIdAndStatusOrderByJoinedAtAsc(challenge.getId(), MemberStatus.ACTIVE);
            int success = members.stream().mapToInt(ChallengeMember::getSuccessDays).sum();
            int total = members.stream().mapToInt(m -> m.getSuccessDays() + m.getFailDays()).sum();
            if (total < minimum) continue;
            rows.add(new Row(challenge, success, total,
                    BigDecimal.valueOf(success).divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP)));
        }
        rows.sort(Comparator.comparing(Row::rate).reversed().thenComparing(Row::success, Comparator.reverseOrder())
                .thenComparing(r -> r.challenge().getId()));
        List<CrossRankingDtos.Item> ranked = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) ranked.add(item(rows.get(i), i + 1));
        int size = requestedSize == null ? 20 : Math.max(1, Math.min(requestedSize, 50));
        int start = cursorStart(ranked, cursor);
        List<CrossRankingDtos.Item> page = ranked.stream().skip(start).limit(size).toList();
        String next = start + page.size() < ranked.size() && !page.isEmpty()
                ? page.get(page.size() - 1).challengeId() : null;
        CrossRankingDtos.Item mine = challengeId == null ? null : ranked.stream()
                .filter(i -> i.challengeId().equals(challengeId.toString())).findFirst().orElse(null);
        return new CrossRankingDtos.Response(mine, page, Instant.now().toString(), next);
    }

    private CrossRankingDtos.Item item(Row row, int rank) {
        Challenge c = row.challenge();
        return new CrossRankingDtos.Item(rank, c.getId().toString(), c.getTitle(), c.getImageUrl(),
                c.getParticipationType().name(), row.rate(), row.success(), row.total());
    }

    private int cursorStart(List<CrossRankingDtos.Item> rows, String cursor) {
        if (cursor == null || cursor.isBlank()) return 0;
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).challengeId().equals(cursor)) return i + 1;
        throw new BusinessException(ErrorCode.CURSOR_INVALID);
    }
}
