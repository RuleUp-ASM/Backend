package com.ruleup.ruleup_backend.me.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.me.dto.MyAppealsResponse;
import com.ruleup.ruleup_backend.verification.domain.Appeal;
import com.ruleup.ruleup_backend.verification.repository.AppealRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 이의 제기 현황(GET /users/me/appeals). 접수는 인증 모듈 소관이고 여기서는 이력만 읽는다(Non-Goals).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MyAppealsService {

    /**
     * 이의는 2026-08-25 개편으로 자동 인용 한 경로로 통합됐다. 트랙 구분이 사라졌지만 API 계약에
     * 남아 있는 필드라 자동 인용을 뜻하는 값을 고정으로 내린다.
     */
    private static final String AUTO_ACCEPT_TRACK = "B";
    private static final String ACCEPTED = "ACCEPTED";

    private final AppealRepository appealRepository;
    private final ChallengeRepository challengeRepository;

    public MyAppealsResponse history(UUID userId) {
        List<Appeal> appeals = appealRepository.findByUserIdOrderByAcceptedAtDesc(userId);
        Map<UUID, Challenge> titles = challengeRepository
                .findAllById(appeals.stream().map(Appeal::getChallengeId).distinct().toList())
                .stream().collect(Collectors.toMap(Challenge::getId, Function.identity()));

        return new MyAppealsResponse(appeals.stream()
                .map(a -> {
                    Challenge c = titles.get(a.getChallengeId());
                    return new MyAppealsResponse.Item(
                            a.getId().toString(),
                            a.getTargetDate().toString(),   // 이미 KST 귀속일이라 변환하지 않는다
                            a.getChallengeId().toString(),
                            c != null ? c.getTitle() : null,   // 하드 삭제된 방은 제목을 복원할 수 없다
                            a.getReason(), AUTO_ACCEPT_TRACK, ACCEPTED);
                })
                .toList());
    }
}
