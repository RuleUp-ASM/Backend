package com.ruleup.ruleup_backend.admin.web;

import com.ruleup.ruleup_backend.admin.domain.AdminAction;
import com.ruleup.ruleup_backend.admin.service.AdminAuditService;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.UUID;

/**
 * 백오피스 접근 통제 — 백오피스 백엔드 4-2.
 *
 * <p>두 가드레일을 <b>한곳에서 구조적으로</b> 보장한다.
 * <ul>
 *   <li><b>일반 회원 접근 성공 0건</b> — 컨트롤러마다 권한 검사를 흩뿌리면 새 엔드포인트를
 *       추가할 때 빠뜨릴 수 있다. 경로 prefix 로 걸어 두면 빠뜨릴 자리가 없다</li>
 *   <li><b>거부도 기록</b> — DENIED 급증이 우회 시도의 신호이므로, 막고 끝내지 않고 남긴다</li>
 * </ul>
 *
 * <p>조작별 상세 기록(대상·다이제스트)은 각 서비스가 따로 남긴다. 여기서는 "누가 어느 경로에
 * 접근했는가"만 본다 — 인터셉터는 본문을 읽을 수 없고, 읽으면 컨트롤러가 다시 못 읽는다.
 */
@Component
@RequiredArgsConstructor
public class AdminAccessInterceptor implements HandlerInterceptor {

    private final UserRepository userRepository;
    private final AdminAuditService auditService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        UUID userId = currentUserId();
        if (userId == null) throw new BusinessException(ErrorCode.LOGIN_REQUIRED);

        boolean operator = userRepository.findById(userId)
                .map(u -> u.isOperator() && !u.isWithdrawn())
                .orElse(false);

        if (!operator) {
            auditService.denied(userId, actionOf(request), request.getRequestURI());
            throw new BusinessException(ErrorCode.ADMIN_FORBIDDEN);
        }
        return true;
    }

    /**
     * 경로에서 조작 종류를 유추한다. 거부된 요청은 컨트롤러에 닿지 않아 서비스가 남길 수 없으므로,
     * 여기서라도 "무엇을 하려 했는지"를 남겨야 DENIED 로그가 쓸모 있어진다.
     */
    private AdminAction actionOf(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.contains("/sanctions")) return AdminAction.SANCTION_APPLY;
        if (path.contains("/close")) return AdminAction.CHALLENGE_CLOSE;
        if (path.contains("/resolve")) return AdminAction.REPORT_RESOLVE;
        if (path.contains("/anomalies")) return AdminAction.ANOMALY_VIEW;
        if (path.contains("/outage-relief")) return AdminAction.OUTAGE_RELIEF;
        if (path.contains("/notices")) return AdminAction.OPS_NOTICE;
        if (path.contains("/reports")) return AdminAction.REPORT_QUEUE_VIEW;
        return AdminAction.USER_VIEW;
    }

    private UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof String principal))
            return null;
        try {
            return UUID.fromString(principal);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
