package com.ruleup.ruleup_backend.devlog;

import com.ruleup.ruleup_backend.security.JwtProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * <b>시연·개발 확인용 요청 로그.</b> 요청 한 건을 콘솔에 한 덩어리로 찍는다.
 *
 * <pre>
 * ┌─ #7 ▶ POST /api/v1/challenges  user=42
 * │  query : sort=POPULAR
 * │  body  : {"title":"아침 러닝"}
 * └─ #7 ◀ 201 CREATED  118ms
 *    body  : {"challengeId":7}
 * </pre>
 *
 * <p>보안 필터보다 <b>바깥</b>에 달려서 401/403 으로 튕긴 요청도 찍힌다. 그래서 인증 주체를
 * SecurityContext 에서 읽지 않는다 — 체인이 끝나면 이미 비워져 있기 때문에, 여기서는
 * Authorization 헤더를 직접 한 번 더 열어 subject 만 꺼낸다.
 *
 * <p>임시 코드다. 끌 때는 {@code APP_REQUEST_LOG_ENABLED=false}, 지울 때는 이 패키지
 * ({@code devlog}) 통째로 삭제하면 된다 — 다른 코드가 참조하지 않는다.
 */
public class RequestLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("devlog");

    /** 시연 화면을 채우는 잡음(정적 파일·문서·헬스체크)은 찍지 않는다. */
    private static final String[] SKIP_PREFIXES = {
            "/actuator", "/swagger-ui", "/v3/api-docs", "/swagger-resources", "/files", "/favicon.ico"
    };

    /** 값이 그대로 콘솔에 남으면 곤란한 필드. 이름만 보이고 값은 가린다. */
    private static final Pattern SECRET_FIELD = Pattern.compile(
            "\"(\\w*(?i:password|secret|token|credential|authorization)\\w*)\"\\s*:\\s*\"[^\"]*\"");

    private static final AtomicLong SEQ = new AtomicLong();

    private final JwtProvider jwtProvider;
    private final boolean logBody;
    private final int maxBody;

    public RequestLogFilter(JwtProvider jwtProvider, boolean logBody, int maxBody) {
        this.jwtProvider = jwtProvider;
        this.logBody = logBody;
        this.maxBody = maxBody;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        for (String prefix : SKIP_PREFIXES) {
            if (uri.startsWith(prefix)) return true;
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // 멀티파트(이미지 업로드)는 본문을 캐싱하지 않는다 — 바이트를 메모리에 두 번 들고 있을 이유가 없고,
        // 찍어 봐야 읽을 수 없는 바이너리다.
        boolean multipart = request.getContentType() != null
                && request.getContentType().toLowerCase().startsWith("multipart/");

        // 캐시 상한을 어차피 잘라 찍을 길이에 맞춘다 — 큰 본문을 통째로 메모리에 들고 있을 이유가 없다.
        HttpServletRequest req = (logBody && !multipart)
                ? new ContentCachingRequestWrapper(request, Math.max(maxBody, 1024)) : request;
        ContentCachingResponseWrapper res = new ContentCachingResponseWrapper(response);

        long id = SEQ.incrementAndGet();
        long startedAt = System.nanoTime();
        Throwable failure = null;
        try {
            chain.doFilter(req, res);
        } catch (Throwable t) {
            failure = t;
            throw t;
        } finally {
            long tookMs = (System.nanoTime() - startedAt) / 1_000_000;
            try {
                log.info("{}", render(id, req, res, tookMs, multipart, failure));
            } catch (Exception ignored) {
                // 로그를 만들다 죽어서 실제 요청까지 망치는 일은 없어야 한다.
            }
            // 캐싱 래퍼가 붙잡고 있는 응답 본문을 실제 소켓으로 흘려보낸다. 빠뜨리면 클라가 빈 응답을 받는다.
            res.copyBodyToResponse();
        }
    }

    private String render(long id, HttpServletRequest req, ContentCachingResponseWrapper res,
                          long tookMs, boolean multipart, Throwable failure) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("\n┌─ #").append(id).append(' ').append("▶ ")
          .append(req.getMethod()).append(' ').append(req.getRequestURI())
          .append("  user=").append(subject(req));

        if (req.getQueryString() != null) {
            sb.append("\n│  query : ").append(req.getQueryString());
        }
        if (multipart) {
            sb.append("\n│  body  : (multipart, 생략)");
        } else if (req instanceof ContentCachingRequestWrapper w) {
            String body = mask(new String(w.getContentAsByteArray(), StandardCharsets.UTF_8));
            if (!body.isBlank()) sb.append("\n│  body  : ").append(body);
        }

        int status = res.getStatus();
        sb.append("\n└─ #").append(id).append(' ').append(status >= 400 ? "✖ " : "◀ ").append(status);
        HttpStatus resolved = HttpStatus.resolve(status);
        if (resolved != null) sb.append(' ').append(resolved.name());
        sb.append("  ").append(tookMs).append("ms");

        if (logBody) {
            String body = mask(new String(res.getContentAsByteArray(), StandardCharsets.UTF_8));
            if (!body.isBlank()) sb.append("\n   body  : ").append(body);
        }
        if (failure != null) {
            sb.append("\n   throw : ").append(failure.getClass().getSimpleName())
              .append(": ").append(failure.getMessage());
        }
        return sb.toString();
    }

    /** 토큰의 subject(=userId). 없거나 못 읽으면 상태만 남긴다. */
    private String subject(HttpServletRequest req) {
        String header = req.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) return "-";
        try {
            return String.valueOf(jwtProvider.parse(header.substring(7)).getSubject());
        } catch (Exception e) {
            return "(토큰 무효)";
        }
    }

    private String mask(String raw) {
        String body = raw.length() > maxBody ? raw.substring(0, maxBody) + "…(생략)" : raw;
        return SECRET_FIELD.matcher(body).replaceAll("\"$1\":\"***\"").replaceAll("\\s*\\n\\s*", " ");
    }
}
