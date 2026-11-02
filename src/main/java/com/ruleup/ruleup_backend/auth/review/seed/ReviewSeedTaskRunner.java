package com.ruleup.ruleup_backend.auth.review.seed;

import com.ruleup.ruleup_backend.auth.review.ReviewAccountPolicy;
import com.ruleup.ruleup_backend.sanction.ReviewAccounts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * 심사 계정 시드 태스크({@code app.runtime.role=review_seed})의 진입점 (심사 계정 테크 스펙 5-8).
 *
 * <p>일반 API 와 같은 이미지·DB 계정으로 일회성 태스크를 띄우고 인자로 모드를 넘긴다.
 * <pre>
 *   --mode=create --accounts=6 --evaluation-end=2026-11-20
 *   --mode=reset [--confirm-round=2026110301]
 * </pre>
 * 리셋은 서버와 같은 설정 빈({@link ReviewAccountPolicy} · {@link ReviewAccounts})을 쓴다 — 태스크에도 서버와 같은
 * {@code REVIEW_LOGIN_*} · {@code REVIEW_ACCOUNT_IDS} 를 넣는다. 종료 코드: 0 성공 · 1 실패(아무것도 바뀌지 않았다) ·
 * 2 커밋됐지만 마무리 일부 실패(다시 돌리지 않는다 — 로그의 결과 줄을 쓴다). 출력은 round · UUID ·
 * 건수뿐이다 — 코드는 시드가 만들지도 받지도 않는다(5-7).
 */
@Slf4j
@Component
@ConditionalOnExpression("'${app.runtime.role:api}'.equalsIgnoreCase('review_seed')")
public class ReviewSeedTaskRunner implements ApplicationRunner {

    private final ConfigurableApplicationContext context;
    private final ReviewSeedService seed;
    private final ReviewAccounts reviewAccounts;
    private final ReviewAccountPolicy policy;

    public ReviewSeedTaskRunner(ConfigurableApplicationContext context, ReviewSeedService seed,
                                ReviewAccounts reviewAccounts, ReviewAccountPolicy policy) {
        this.context = context;
        this.seed = seed;
        this.reviewAccounts = reviewAccounts;
        this.policy = policy;
    }

    @Override
    public void run(ApplicationArguments args) {
        int code;
        try {
            ReviewSeedCommand command = ReviewSeedCommand.parse(args.getSourceArgs());
            boolean complete = switch (command) {
                case ReviewSeedCommand.Create c -> seed.create(c.accounts(), c.evaluationEnd()).followUpsComplete();
                case ReviewSeedCommand.Reset r -> seed.reset(reviewAccounts, policy, r.confirmRound()).followUpsComplete();
            };
            // 2 = 데이터는 커밋됐고 마무리(통계·탐색 인덱스)만 남았다 — 다시 돌리지 않는다. 결과 줄(review_seed_created /
            // review_seed_reset)의 round·UUID 를 그대로 쓰고, 남은 값은 5분 통계 보정·탐색 대조가 맞춘다
            code = complete ? 0 : 2;
        } catch (ReviewSeedException e) {
            log.error("review_seed_failed reason={} message={}", e.reason(), e.getMessage());
            code = 1;
        } catch (RuntimeException e) {
            log.error("review_seed_failed reason=ERROR", e);
            code = 1;
        }
        int exit = code;
        System.exit(SpringApplication.exit(context, () -> exit));
    }
}
