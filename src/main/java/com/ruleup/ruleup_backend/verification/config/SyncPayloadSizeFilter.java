package com.ruleup.ruleup_backend.verification.config;

import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.response.ApiResponse;
import com.ruleup.ruleup_backend.common.error.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * sync 요청 본문 크기 상한 (백엔드 테크스펙 §5 "요청 크기 상한").
 *
 * <p>경로상의 상한(CDN 100MB급, ALB 무제한에 가까움)은 병목이 아니다. 실제 병목은 <b>JSON 본문을 통째로
 * 힙에 올리는 파싱 메모리</b>이고, Tomcat 의 기본 POST 제한은 form 인코딩에만 적용돼 JSON 에는 사실상
 * 상한이 없다. 동시 요청 수 × 요청 크기가 그대로 힙 압박이 되므로 명시적으로 걸어야 한다.
 *
 * <p>두 겹으로 막는다.
 * <ol>
 *   <li>{@code Content-Length} 가 상한을 넘으면 <b>읽기 전에</b> 반려한다.</li>
 *   <li>길이를 모르는 요청(chunked·gzip 해제분)은 읽어 나가면서 누적 바이트를 세고 상한을 넘는 순간 끊는다.</li>
 * </ol>
 * gzip 요청은 {@link SyncRequestDecompressFilter} 가 먼저 풀어 주므로 여기서 세는 바이트가 곧
 * <b>압축 해제 후 누적 바이트</b>다 — 스펙이 요구하는 이중 상한(본문·해제 후)이 이 배치로 성립한다.
 * 압축률이 높은 요청 하나로 힙을 고갈시키는 것을 막기 위함이다.
 * 어느 쪽이든 본문 전체가 힙에 올라오지 않는다. 클라는 413 을 받으면 구간을 반으로 쪼개 재전송한다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)   // gzip 해제(HIGHEST+10) 다음 — 해제 후 바이트를 센다
@RequiredArgsConstructor
public class SyncPayloadSizeFilter extends OncePerRequestFilter {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SYNC_PATH = "/api/v1/verifications/sync";

    private final VerificationProperties properties;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !SYNC_PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long limit = properties.maxPayloadBytes();
        if (request.getContentLengthLong() > limit) {
            writeTooLarge(response);
            return;
        }
        try {
            chain.doFilter(new LimitedBodyRequest(request, limit), response);
        } catch (RuntimeException | ServletException | IOException e) {
            // 스트림에서 던진 신호는 파서(Jackson·메시지 컨버터)가 자기 예외로 감싸 올려보낸다.
            // 원인 사슬을 따라가 우리가 끊은 것인지 확인하고, 아니면 그대로 올린다.
            if (!causedByPayloadTooLarge(e)) throw e;
            response.reset();
            writeTooLarge(response);
        }
    }

    private void writeTooLarge(HttpServletResponse response) throws IOException {
        ErrorCode code = ErrorCode.SYNC_PAYLOAD_TOO_LARGE;
        response.setStatus(code.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(JSON.writeValueAsString(ApiResponse.fail(ErrorResponse.of(code))));
    }

    private boolean causedByPayloadTooLarge(Throwable e) {
        for (Throwable t = e; t != null && t != t.getCause(); t = t.getCause()) {
            if (t instanceof SyncPayloadTooLargeException) return true;
        }
        return false;
    }

    /** 본문을 읽어 나가며 누적 바이트를 세는 래퍼. 상한을 넘으면 즉시 끊는다. */
    private static class LimitedBodyRequest extends HttpServletRequestWrapper {
        private final long limit;

        LimitedBodyRequest(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream delegate = super.getInputStream();
            return new ServletInputStream() {
                private long read;

                private int count(int n) {
                    if (n > 0 && (read += n) > limit) throw new SyncPayloadTooLargeException();
                    return n;
                }

                @Override public int read() throws IOException {
                    int b = delegate.read();
                    if (b >= 0) count(1);
                    return b;
                }

                @Override public int read(byte[] b, int off, int len) throws IOException {
                    return count(delegate.read(b, off, len));
                }

                @Override public boolean isFinished() { return delegate.isFinished(); }
                @Override public boolean isReady() { return delegate.isReady(); }
                @Override public void setReadListener(ReadListener listener) { delegate.setReadListener(listener); }
            };
        }
    }
}
