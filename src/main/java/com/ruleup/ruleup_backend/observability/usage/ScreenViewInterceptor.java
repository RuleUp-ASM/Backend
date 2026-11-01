package com.ruleup.ruleup_backend.observability.usage;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.security.Principal;
import java.util.UUID;

/**
 * 화면 API 를 성공적으로 받은 회원을 {@link ScreenViewRecorder} 에 넘긴다. 컨트롤러는 손대지 않는다.
 *
 * <p>GET 이고 2xx 로 끝난 요청만 센다 — 권한 없음(403)·없는 방(404)은 화면을 본 것이 아니다. 화면은 스프링이 고른
 * 경로 패턴으로 가린다({@link ScreenViewRecorder#SCREENS}). 회원 ID 는 앱 JWT 인증의 주체(문자열 UUID)다.
 */
@Component
public class ScreenViewInterceptor implements HandlerInterceptor {

    private final ScreenViewRecorder recorder;

    public ScreenViewInterceptor(ScreenViewRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (ex != null || !"GET".equals(request.getMethod())) return;
        int status = response.getStatus();
        if (status < 200 || status >= 300) return;
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String screen = pattern == null ? null : ScreenViewRecorder.SCREENS.get(pattern.toString());
        if (screen == null) return;
        UUID userId = userId(request.getUserPrincipal());
        if (userId != null) recorder.record(userId, screen);
    }

    private static UUID userId(Principal principal) {
        if (principal == null) return null;
        try {
            return UUID.fromString(principal.getName());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
