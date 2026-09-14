package com.ruleup.ruleup_backend.verification.config;

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
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.ruleup.ruleup_backend.verification.service.VerificationMetrics;

import java.io.IOException;
import java.util.zip.GZIPInputStream;

/**
 * sync 요청 본문 gzip 해제 (API 명세: {@code Content-Encoding: gzip} 지원, 선택).
 *
 * <p>Spring Boot 는 <b>응답</b> 압축만 다루고 요청 본문은 풀어 주지 않는다. 그래서 명세에 적힌 gzip 지원이
 * 실제로는 동작하지 않았고, 크기 상한도 "압축 후 바이트"에만 걸려 있었다 —
 * 압축률이 높은 요청 하나로 힙을 고갈시킬 수 있는 형태다.
 *
 * <p>이 필터가 <b>{@link SyncPayloadSizeFilter} 보다 먼저</b> 돌아 스트림을 풀어 준다. 그러면
 * 뒤 필터가 세는 바이트가 자연히 "압축 해제 후 누적 바이트"가 된다. 해제된 스트림을 통째로
 * 버퍼링하지 않고 그대로 흘려보내므로, 상한을 넘는 순간 읽기가 끊겨 폭탄 본문이 힙에 올라오지 않는다.
 *
 * <h4>압축된 본문도 여기서 막는다</h4>
 * 해제 래퍼는 {@code Content-Length} 를 감춘다(압축 후 길이라 파서가 본문을 잘라 버리기 때문). 그래서
 * <b>뒤 필터는 압축 전 크기를 볼 방법이 없다</b> — 그대로 두면 스펙이 요구한 이중 상한 중 "본문 바이트"
 * 쪽이 사실상 무제한이 된다. 압축된 본문만으로도 회선·버퍼를 밀어낼 수 있으므로, 감추기 전에 원본
 * {@code Content-Length} 를 보고, 길이를 모르는 chunked 요청은 <b>해제 이전 스트림</b>에서 누적
 * 바이트를 세어 같은 상한에 건다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class SyncRequestDecompressFilter extends OncePerRequestFilter {

    private static final String SYNC_PATH = "/api/v1/verifications/sync";

    private final VerificationProperties properties;
    private final VerificationMetrics metrics;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!SYNC_PATH.equals(request.getRequestURI())) return true;
        String encoding = request.getHeader("Content-Encoding");
        return encoding == null || !encoding.toLowerCase().contains("gzip");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long limit = properties.maxPayloadBytes();
        // 압축된 본문 자체가 상한을 넘으면 풀어 볼 것도 없다. 아래에서 Content-Length 를 감추므로
        // 이 검사를 여기서 하지 않으면 압축 전 크기는 아무 데서도 걸리지 않는다.
        if (request.getContentLengthLong() > limit) {
            metrics.payloadRejected();
            SyncPayloadSizeFilter.renderTooLarge(response);
            return;
        }
        chain.doFilter(new GzipRequest(request, limit), response);
    }

    /**
     * 해제된 본문을 내려주는 래퍼. {@code Content-Length} 는 압축 후 길이라 더는 맞지 않으므로 -1 로 감춘다
     * — 그대로 두면 파서가 압축 길이만큼만 읽고 본문을 잘라 버린다.
     */
    private static class GzipRequest extends HttpServletRequestWrapper {

        private final long limit;

        GzipRequest(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override public int getContentLength() { return -1; }
        @Override public long getContentLengthLong() { return -1; }
        @Override public String getHeader(String name) {
            // 해제 이후에는 압축이 아니다. 아래 필터·파서가 다시 풀려 하지 않게 지운다.
            if ("Content-Encoding".equalsIgnoreCase(name) || "Content-Length".equalsIgnoreCase(name)) return null;
            return super.getHeader(name);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            // 길이를 모르는(chunked) 요청은 읽어 나가며 <b>압축된</b> 바이트를 센다.
            // 해제 이전 스트림이라, 여기서 끊으면 폭탄 본문이 해제 버퍼에도 올라오지 않는다.
            GZIPInputStream gz = new GZIPInputStream(new LimitedRawStream(super.getInputStream(), limit));
            return new ServletInputStream() {
                private boolean finished;

                @Override public int read() throws IOException {
                    int b = gz.read();
                    if (b < 0) finished = true;
                    return b;
                }

                @Override public int read(byte[] b, int off, int len) throws IOException {
                    int n = gz.read(b, off, len);
                    if (n < 0) finished = true;
                    return n;
                }

                @Override public boolean isFinished() { return finished; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("비동기 읽기는 지원하지 않는다");
                }
            };
        }
    }

    /** 해제 <b>이전</b> 바이트를 세는 스트림. 상한을 넘는 순간 읽기를 끊는다. */
    private static class LimitedRawStream extends java.io.InputStream {

        private final java.io.InputStream delegate;
        private final long limit;
        private long read;

        LimitedRawStream(java.io.InputStream delegate, long limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

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

        @Override public void close() throws IOException { delegate.close(); }
    }
}
