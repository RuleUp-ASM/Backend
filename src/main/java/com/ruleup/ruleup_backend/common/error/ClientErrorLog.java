package com.ruleup.ruleup_backend.common.error;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

import java.util.regex.Pattern;

/**
 * 4xx 응답 한 건을 한 줄로 남긴다 — 운영 대시보드가 이 줄을 Logs Insights 로 묶어 「어떤 4xx 가 어느 API 에서
 * 얼마나」를 보여 준다.
 *
 * <p>CloudWatch 지표로 내보내지 않는 이유: 오류 코드가 190종이 넘어 태그 조합마다 과금되는 지표로는 비싸다.
 * 로그 한 줄은 거의 공짜이고, 집계는 화면을 열 때만 한다.
 *
 * <p>경로는 실제 URI 가 아니라 <b>경로 패턴</b>이다. id 가 박힌 URI 를 그대로 남기면 사용자마다 다른 줄이 되어
 * 묶이지 않는다. 보안 필터처럼 디스패처 앞이라 패턴이 없으면 UUID·숫자 조각을 {@code {id}} 로 바꾼다.
 * 5xx 는 남기지 않는다 — ERROR 로그와 5xx 경보가 따로 본다.
 */
@Slf4j
public final class ClientErrorLog {

    private static final Pattern ID_SEGMENT = Pattern.compile(
            "/(?:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|\\d+)(?=/|$)");

    private ClientErrorLog() {}

    public static void record(HttpServletRequest request, ErrorCode code) {
        int status = code.getStatus().value();
        if (status < 400 || status >= 500) return;
        String method = request == null ? "-" : request.getMethod();
        log.info("client_error status={} code={} method={} route={}", status, code.name(), method, route(request));
    }

    /** 예외 처리기처럼 요청 객체를 들고 있지 않은 곳에서 쓴다. */
    public static void record(ErrorCode code) {
        var attrs = RequestContextHolder.getRequestAttributes();
        record(attrs instanceof ServletRequestAttributes s ? s.getRequest() : null, code);
    }

    private static String route(HttpServletRequest request) {
        if (request == null) return "-";
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern != null) return pattern.toString();
        return ID_SEGMENT.matcher(request.getRequestURI()).replaceAll("/{id}");
    }
}
