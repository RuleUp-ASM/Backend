package com.ruleup.ruleup_backend.observability;

import com.ruleup.ruleup_backend.challenge.draft.ChallengeDraft;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashMap;
import java.util.Map;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 서비스 지표 — 가입·로그인 / 챌린지 참여(초안·생성·가입) / 인증 시도 (노션 「RuleUp 모니터링」 2절 서비스 지표).
 *
 * <p>서버 지표(오류·지연·적체)는 「서버가 아픈가」를 보고, 이건 「사람들이 쓰고 있는가」를 본다. 대시보드에서
 * 둘이 섞이지 않도록 이름을 {@code biz.} 로 시작한다. 오늘 인증 성공률 게이지는
 * {@link VerificationRateMetrics} 에 있다.
 *
 * <p>태그 값은 전부 아래 고정 열거다 — CloudWatch 는 태그 조합마다 지표 하나로 과금하므로 사용자 ID·사유
 * 같은 값은 절대 싣지 않는다. 카운터는 생성자에서 미리 만들어 둔다. 한 번도 안 일어난 값이 「0」이 아니라
 * 「없음」으로 보이면 경보가 데이터 부족 상태에 머문다.
 */
@Component
public class BusinessMetrics {

    private final Counter signupSuccess;
    private final Counter signupFailure;
    private final Counter loginExisting;
    private final Counter loginNewUser;
    private final Counter loginFailure;
    private final Counter attemptSync;
    private final Counter attemptManual;
    private final Map<String, Counter> drafts = new HashMap<>();
    private final Map<String, Counter> created = new HashMap<>();
    private final Counter joinedExplore;
    private final Counter joinedInvite;

    public BusinessMetrics(MeterRegistry registry) {
        // 성공은 계정이 실제로 생기거나(신규) 살아난(복귀) 경우만이다. 동시 가입 경합으로 기존 계정 로그인에
        // 수렴한 요청은 가입이 아니므로 어느 쪽에도 세지 않는다 — 세면 가입 수가 경합 횟수만큼 부풀어 오른다.
        this.signupSuccess = signup(registry, "success");
        this.signupFailure = signup(registry, "failure");
        this.loginExisting = login(registry, "existing");
        this.loginNewUser = login(registry, "new_user");
        this.loginFailure = login(registry, "failure");
        this.attemptSync = attempt(registry, "sync");
        this.attemptManual = attempt(registry, "manual");
        // 초안 — AI 는 LLM 을 부르므로 결과(성공·차단·LLM 실패)를 가른다. 템플릿·복제는 실패할 일이 없다.
        for (String result : new String[]{"success", "blocked", "failure"}) drafts.put("ai:" + result, draft(registry, "ai", result));
        drafts.put("template:success", draft(registry, "template", "success"));
        drafts.put("clone:success", draft(registry, "clone", "success"));
        for (ChallengeDraft.Origin origin : ChallengeDraft.Origin.values())
            for (String edited : new String[]{"yes", "no"})
                created.put(tag(origin) + ":" + edited, Counter.builder("biz.challenge.created")
                        .description("챌린지 생성 — 초안 경로(ai·template·clone)와 초안에서 제목·설명·목표값을 고쳤는지")
                        .tag("origin", tag(origin)).tag("edited", edited).register(registry));
        this.joinedExplore = joined(registry, "explore");
        this.joinedInvite = joined(registry, "invite");
    }

    private static Counter draft(MeterRegistry registry, String origin, String result) {
        return Counter.builder("biz.challenge.draft")
                .description("챌린지 초안 — AI(LLM 호출)·추천 탭 템플릿·복제, AI 는 성공·차단·LLM 실패")
                .tag("origin", origin).tag("result", result).register(registry);
    }

    private static Counter joined(MeterRegistry registry, String source) {
        return Counter.builder("biz.challenge.joined")
                .description("챌린지 가입 — 탐색·상세에서 직접(explore)과 초대 링크(invite). 거절된 가입은 세지 않는다")
                .tag("source", source).register(registry);
    }

    private static String tag(ChallengeDraft.Origin origin) {
        return origin.name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 커밋된 일만 센다 — 트랜잭션 안에서 바로 올리면 롤백된 생성·가입이 지표에 남는다.
     * 트랜잭션 밖이면 바로 올린다.
     */
    private static void afterCommit(Counter counter) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { counter.increment(); }
            });
        } else {
            counter.increment();
        }
    }

    private static Counter signup(MeterRegistry registry, String result) {
        return Counter.builder("biz.signup")
                .description("가입 요청 결과 — 신규·복귀 계정 생성(success)과 오류로 끝난 요청(failure)")
                .tag("result", result).register(registry);
    }

    private static Counter login(MeterRegistry registry, String outcome) {
        return Counter.builder("biz.login")
                .description("소셜 로그인 결과 — 기존 회원(existing)·가입으로 넘어감(new_user)·실패(failure)")
                .tag("outcome", outcome).register(registry);
    }

    private static Counter attempt(MeterRegistry registry, String method) {
        return Counter.builder("biz.verification.attempt")
                .description("인증 시도 — 접수된 자동 인증 전송(sync)과 성공한 수동 체크(manual)")
                .tag("method", method).register(registry);
    }

    /** 신규 가입 또는 탈퇴 계정 복귀가 끝났다. */
    public void signupSucceeded() { signupSuccess.increment(); }

    /** 가입 요청이 오류로 끝났다(입력 검증·정지 계정·서버 오류 모두). */
    public void signupFailed() { signupFailure.increment(); }

    /** 기존 회원이 로그인했다. */
    public void loginExisting() { loginExisting.increment(); }

    /** 처음 온 소셜 계정이라 가입 토큰을 내줬다 — 가입 퍼널의 입구다. */
    public void loginNewUser() { loginNewUser.increment(); }

    /** 로그인이 오류로 끝났다(IdP 실패·정지 계정·요청 형식 모두). */
    public void loginFailed() { loginFailure.increment(); }

    /** 자동 인증 전송(sync) 한 건을 끝까지 처리했다. */
    public void syncAttempt() { attemptSync.increment(); }

    /** 수동 체크 한 건이 접수됐다. */
    public void manualAttempt() { attemptManual.increment(); }

    /** 초안을 저장했다(AI 는 LLM 결과가 쓸 만해 초안이 된 경우). */
    public void draftCreated(ChallengeDraft.Origin origin) { afterCommit(drafts.get(tag(origin) + ":success")); }

    /** AI 초안 요청이 정책 차단(Step1·2)으로 끝났다. */
    public void aiDraftBlocked() { drafts.get("ai:blocked").increment(); }

    /** AI 초안 요청이 LLM 장애·파싱 실패·제목 없음으로 끝났다. */
    public void aiDraftFailed() { drafts.get("ai:failure").increment(); }

    /** 초안으로 챌린지를 만들었다. edited — 초안의 제목·설명·목표값 중 하나라도 고쳤는지. */
    public void challengeCreated(ChallengeDraft.Origin origin, boolean edited) {
        afterCommit(created.get(tag(origin) + ":" + (edited ? "yes" : "no")));
    }

    /** 가입이 끝났다(커밋 뒤 호출). invited — 초대 링크로 들어왔는지. */
    public void joined(boolean invited) { (invited ? joinedInvite : joinedExplore).increment(); }
}
