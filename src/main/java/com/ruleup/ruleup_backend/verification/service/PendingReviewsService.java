package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.service.ChallengeQueryService;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.verification.domain.FallbackApprovalStatus;
import com.ruleup.ruleup_backend.verification.domain.Objection;
import com.ruleup.ruleup_backend.verification.domain.ObjectionStatus;
import com.ruleup.ruleup_backend.verification.domain.VerificationDaily;
import com.ruleup.ruleup_backend.verification.domain.VerificationMethodResult;
import com.ruleup.ruleup_backend.verification.dto.PendingReviewsResponse;
import com.ruleup.ruleup_backend.verification.repository.ObjectionRepository;
import com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository;
import com.ruleup.ruleup_backend.verification.repository.VerificationMethodResultRepository;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 방장/공동 관리자 처리 대기함(§pending-reviews): 폴백 수동 인증(PENDING_APPROVAL)과 이의 제기(PENDING)를
 * 하나의 목록(제출 시각 오름차순)으로 반환. 처리 주체·행위가 동일해 한 대기함으로 묶는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PendingReviewsService {

    private final ChallengeQueryService challengeQuery;
    private final VerificationDailyRepository dailyRepo;
    private final VerificationMethodResultRepository methodResultRepo;
    private final ObjectionRepository objectionRepo;
    private final UserRepository userRepository;

    public PendingReviewsResponse list(UUID adminId, UUID challengeId) {
        Challenge c = challengeQuery.findActiveChallenge(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
        if (!challengeQuery.isChallengeAdmin(c, adminId))
            throw new BusinessException(ErrorCode.NOT_CHALLENGE_ADMIN);

        List<VerificationDaily> fallbacks =
                dailyRepo.findByChallengeIdAndFallbackApprovalStatus(challengeId, FallbackApprovalStatus.PENDING);
        List<Objection> objections =
                objectionRepo.findByChallengeIdAndStatusOrderByCreatedAtAsc(challengeId, ObjectionStatus.PENDING);

        // 닉네임 일괄 조회(익명 챌린지는 마스킹).
        List<UUID> userIds = Stream.concat(
                fallbacks.stream().map(VerificationDaily::getUserId),
                objections.stream().map(Objection::getUserId)).distinct().toList();
        Map<UUID, User> userMap = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        List<PendingReviewsResponse.Item> items = new ArrayList<>();

        for (VerificationDaily d : fallbacks) {
            VerificationMethodResult mr = methodResultRepo
                    .findByVerificationDailyIdAndMethod(d.getId(), d.getMethod()).orElse(null);
            Map<String, Object> ev = (mr != null) ? mr.getEvidence() : null;
            Instant submittedAt = (mr != null && mr.getLastEvaluatedAt() != null)
                    ? mr.getLastEvaluatedAt() : d.getUpdatedAt();
            items.add(new PendingReviewsResponse.Item(
                    "FALLBACK", d.getId().toString(), d.getUserId().toString(),
                    nickname(c, userMap.get(d.getUserId())),
                    d.getTargetDate().toString(),
                    ev != null ? asStr(ev.get("content")) : null,
                    ev != null ? asStr(ev.get("imageUrl")) : null,
                    submittedAt != null ? submittedAt.toString() : null,
                    null));
        }
        for (Objection o : objections) {
            items.add(new PendingReviewsResponse.Item(
                    "OBJECTION", o.getId().toString(), o.getUserId().toString(),
                    nickname(c, userMap.get(o.getUserId())),
                    o.getTargetDate().toString(), o.getContent(), o.getImageUrl(),
                    o.getCreatedAt() != null ? o.getCreatedAt().toString() : null,
                    o.getDeadline() != null ? o.getDeadline().toString() : null));
        }

        items.sort(Comparator.comparing(PendingReviewsResponse.Item::submittedAt,
                Comparator.nullsLast(Comparator.naturalOrder())));

        return new PendingReviewsResponse(challengeId.toString(), items.size(), items);
    }

    private String nickname(Challenge c, User u) {
        String nick = (u != null) ? u.getNickname() : null;
        return c.getAnonymity().maskNickname(nick);
    }

    private String asStr(Object o) { return (o != null) ? o.toString() : null; }
}
