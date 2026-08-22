package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.service.ChallengeQueryService;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.verification.domain.Objection;
import com.ruleup.ruleup_backend.verification.domain.ObjectionStatus;
import com.ruleup.ruleup_backend.verification.dto.PendingReviewsResponse;
import com.ruleup.ruleup_backend.verification.repository.ObjectionRepository;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 방장/공동 관리자 처리 대기함: 처리 대기 중인 이의 제기(PENDING)를 제출 시각 오름차순으로 반환.
 *
 * <p>예전엔 자동 방의 수동 폴백(PENDING_APPROVAL)도 같이 실렸지만, 폴백은 폐기되고
 * 자동 방의 실패 구제는 이의 제기 하나로 일원화됐다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PendingReviewsService {

    private final ChallengeQueryService challengeQuery;
    private final ObjectionRepository objectionRepo;
    private final UserRepository userRepository;

    public PendingReviewsResponse list(UUID adminId, UUID challengeId) {
        Challenge c = challengeQuery.findActiveChallenge(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
        if (!challengeQuery.isChallengeAdmin(c, adminId))
            throw new BusinessException(ErrorCode.NOT_CHALLENGE_ADMIN);

        List<Objection> objections =
                objectionRepo.findByChallengeIdAndStatusOrderByCreatedAtAsc(challengeId, ObjectionStatus.PENDING);

        // 닉네임 일괄 조회(익명 챌린지는 마스킹).
        List<UUID> userIds = objections.stream().map(Objection::getUserId).distinct().toList();
        Map<UUID, User> userMap = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        List<PendingReviewsResponse.Item> items = new ArrayList<>();

        for (Objection o : objections) {
            items.add(new PendingReviewsResponse.Item(
                    "OBJECTION", o.getId().toString(), o.getUserId().toString(),
                    nickname(c, userMap.get(o.getUserId())),
                    o.getTargetDate().toString(), o.getContent(), o.getImageUrl(),
                    o.getCreatedAt() != null ? o.getCreatedAt().toString() : null,
                    o.getDeadline() != null ? o.getDeadline().toString() : null));
        }

        return new PendingReviewsResponse(challengeId.toString(), items.size(), items);
    }

    private String nickname(Challenge c, User u) {
        String nick = (u != null) ? u.getNickname() : null;
        return c.getAnonymity().maskNickname(nick);
    }
}
