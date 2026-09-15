package com.ruleup.ruleup_backend.me.service;

import com.ruleup.ruleup_backend.challenge.service.ChallengeTitleResolver;
import com.ruleup.ruleup_backend.me.dto.MyAppealsResponse;
import com.ruleup.ruleup_backend.verification.domain.Appeal;
import com.ruleup.ruleup_backend.verification.repository.AppealRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** Read accepted submissions with their preserved challenge title. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly=true)
public class MyAppealsService {
    private final AppealRepository appeals;
    private final ChallengeTitleResolver titles;
    public MyAppealsResponse history(UUID userId) {
        var history=appeals.findByUserIdOrderByAcceptedAtDesc(userId);
        var names=titles.titlesOf(history.stream().map(Appeal::getChallengeId).toList());
        return new MyAppealsResponse(history.stream().map(a->new MyAppealsResponse.Item(a.getId().toString(),a.getAcceptedAt().toString(),
                a.getTargetDate().toString(),a.getChallengeId().toString(),names.get(a.getChallengeId()),a.getReason())).toList());
    }
}
