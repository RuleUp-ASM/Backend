package com.ruleup.ruleup_backend.observability.sli;

import com.ruleup.ruleup_backend.common.error.ClientErrorLog;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 모든 요청을 SLI 로 분류한다 — 보안 필터보다 바깥에서 돌아 토큰 거절(401)·계정 차단(403)·처리 시간 전체를 본다.
 *
 * <p>4xx 가 정책 거절인지는 응답 코드만으로는 모른다. 그래서 오류 응답을 만드는 모든 길이 지나는
 * {@link ClientErrorLog} 가 요청 속성에 {@link ErrorCode} 를 남기고, 여기서 읽는다.
 * 합성 점검은 {@value #SYNTHETIC_HEADER} 헤더로 스스로를 밝힌다(헤더를 꾸며 보내도 그 요청이 SLI 에서 빠질 뿐이다).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class SliFilter extends OncePerRequestFilter {

    public static final String SYNTHETIC_HEADER = "X-RuleUp-Synthetic";

    private final SliRecorder recorder;

    public SliFilter(SliRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        boolean escaped = false;
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException e) {
            escaped = true;   // 처리기 밖으로 새어 나간 예외 — 컨테이너가 500 으로 끝낸다
            throw e;
        } finally {
            long millis = (System.nanoTime() - started) / 1_000_000L;
            int status = escaped ? 500 : response.getStatus();
            String path = request.getRequestURI().substring(request.getContextPath().length());
            SliOperation op = SliOperation.match(request.getMethod(), path);
            ErrorCode code = request.getAttribute(ClientErrorLog.ERROR_CODE_ATTRIBUTE) instanceof ErrorCode c ? c : null;
            boolean synthetic = request.getHeader(SYNTHETIC_HEADER) != null;
            recorder.record(op, status, SliOutcome.classify(status, code, synthetic), millis);
        }
    }
}
