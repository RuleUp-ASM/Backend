package com.ruleup.ruleup_backend.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 서비스 지표 — 가입·로그인·인증 시도 (노션 「RuleUp 모니터링」 2절 서비스 지표).
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
}
