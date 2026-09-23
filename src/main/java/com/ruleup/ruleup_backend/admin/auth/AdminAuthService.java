package com.ruleup.ruleup_backend.admin.auth;

import com.ruleup.ruleup_backend.admin.domain.AdminAction;
import com.ruleup.ruleup_backend.admin.service.AdminAuditService;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.config.AppProperties;
import com.ruleup.ruleup_backend.security.JwtProvider;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.OAuthProvider;
import com.ruleup.ruleup_backend.user.domain.User;
import com.ruleup.ruleup_backend.user.domain.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 콘솔 진입 인증 — 백오피스 공통 5-2-1 B.
 *
 * <h4>비밀번호 하나를 받고 실제 운영자 계정의 토큰을 준다</h4>
 * 비밀번호에는 신원이 없다. 그런데 감사 로그는 <b>조작자를 남겨야</b> 하고, 접근 통제는 이미
 * 「운영자 롤을 가진 계정만 통과」로 서 있다. 그래서 진입 비밀번호를 확인한 뒤 <b>설정된 운영자
 * 계정의 액세스 토큰</b>을 발급한다 — 뒤에 오는 필터·인터셉터·감사가 전부 그대로 동작한다.
 *
 * <p>이 방식의 한계를 알고 간다: 운영자가 둘 이상이면 <b>서로를 구분할 수 없다</b>. 공통 오픈
 * 이슈 #2 가 닫히면 이 절이 통째로 대체되며, 그때 바뀌는 것은 여기 한 곳뿐이다.
 *
 * <h4>시도 제한은 프로세스 안에만 있다</h4>
 * 태스크가 여러 개면 한도가 태스크 수만큼 늘어난다. 그래도 두는 이유는 <b>무차별 대입이
 * 무제한으로 돌지 않게</b> 하는 것이 목적이고, 거부는 전부 감사 로그에 {@code DENIED} 로 남아
 * 급증 자체를 탐지할 수 있기 때문이다. 분산 카운터는 이 규모에 과하다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAuthService {

    /** 창 안에서 허용하는 실패 횟수. 사람이 오타를 내는 횟수보다는 넉넉하다. */
    private static final int MAX_ATTEMPTS = 10;
    private static final Duration WINDOW = Duration.ofMinutes(10);

    /**
     * 콘솔 계정의 소셜 신원 — <b>고정값이라 두 번 만들어지지 않는다</b>
     * ({@code uq_users_oauth_identity}). 실제 소셜 계정과 겹칠 수 없는 subject 를 쓴다.
     */
    private static final OAuthProvider CONSOLE_PROVIDER = OAuthProvider.KAKAO;
    private static final String CONSOLE_SUBJECT = "ruleup-admin-console";
    private static final String CONSOLE_NICKNAME = "운영자";

    private final AdminProperties adminProperties;
    private final AppProperties appProperties;
    private final JwtProvider jwtProvider;
    private final UserRepository userRepository;
    private final AdminAuditService auditService;

    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();

    private record Attempts(int count, Instant windowStart) {}

    public record Session(String accessToken, String operatorId, String nickname, String expiresAt) {}

    /**
     * 진입 인증. <b>실패는 전부 감사 로그에 남는다</b> — 거부 급증이 우회 시도의 신호이므로,
     * 막고 끝내지 않고 기록한다.
     *
     * <p>트랜잭션을 여는 이유는 첫 로그인에서 콘솔 계정을 만들 수 있기 때문이다. 감사 기록은
     * {@code REQUIRES_NEW} 라 이 트랜잭션이 뒤집혀도 시도 흔적은 남는다.
     */
    @Transactional
    public Session login(String passcode, String clientKey) {
        if (exceeded(clientKey)) {
            auditService.denied(null, AdminAction.ADMIN_LOGIN, "rate-limited");
            throw new BusinessException(ErrorCode.TOO_MANY_ATTEMPTS);
        }

        if (!adminProperties.isConfigured()) {
            // 미설정이 곧 비활성이다. 이유를 응답으로 알리지 않는다 — 설정 상태도 정보다.
            log.error("운영자 콘솔 진입 설정이 비어 있다. app.admin.passcode / app.admin.operator-id 를 확인한다.");
            fail(clientKey);
            throw new BusinessException(ErrorCode.INVALID_PASSCODE);
        }

        if (passcode == null || !constantTimeEquals(passcode, adminProperties.passcode())) {
            fail(clientKey);
            auditService.denied(null, AdminAction.ADMIN_LOGIN, "invalid-passcode");
            throw new BusinessException(ErrorCode.INVALID_PASSCODE);
        }

        User operator = operator();
        UUID operatorId = operator.getId();

        attempts.remove(clientKey);
        auditService.allowed(operatorId, AdminAction.ADMIN_LOGIN, null, null, null);

        return new Session(jwtProvider.issueAccessToken(operatorId), operatorId.toString(),
                operator.visibleNicknameTo(null), expiresAt().toString());
    }

    /** 새로고침 후 토큰이 아직 살아 있는지. 여기까지 왔다는 것은 인터셉터를 통과했다는 뜻이다. */
    public Session session(UUID operatorId) {
        String nickname = userRepository.findById(operatorId)
                .map(u -> u.visibleNicknameTo(null)).orElse(null);
        return new Session(null, operatorId.toString(), nickname, null);
    }

    /**
     * 세션의 주인이 될 계정. 설정에 id 가 있으면 그것을, 없으면 운영자 롤 계정을 찾고,
     * <b>그마저 없으면 서버가 만든다.</b>
     *
     * <h4>왜 계정이 필요한가 — 로그인 때문이 아니다</h4>
     * 비밀번호만으로 문은 열 수 있다. 계정이 필요한 곳은 <b>문을 열고 난 뒤</b>다.
     * <ul>
     *   <li>{@code outage_reliefs.operator_id} 는 {@code NOT NULL} 이고 {@code users.id} 에
     *       <b>FK 가 걸려 있다</b> — 계정 행이 없으면 장애 구제가 INSERT 자체를 못 한다</li>
     *   <li>감사 로그와 {@code sanctions.operator_id} 는 FK 가 없어 아무 값이나 들어가지만,
     *       가리키는 계정이 없는 id 는 「누가 집행했나」에 답하지 못한다 —
     *       조작 이력 추적이 이 모듈의 존재 이유다</li>
     * </ul>
     *
     * <h4>그래서 사람이 만들지 않는다</h4>
     * 스테이징 RDS 는 사설망에 있어 손으로 넣을 자리가 없고, 배포마다 계정을 먼저 만들라는
     * 요구는 <b>설정과 DB 가 어긋나는 실패</b>를 하나 더 만든다. 첫 로그인 때 서버가 만들고,
     * 그 뒤로는 같은 계정을 계속 쓴다.
     */
    private User operator() {
        if (adminProperties.hasOperatorId()) {
            UUID configured = parseOperatorId();
            return userRepository.findById(configured)
                    .filter(u -> u.isOperator() && !u.isWithdrawn())
                    .orElseThrow(() -> {
                        // 설정은 있는데 그 계정이 운영자가 아니면 문을 열지 않는다.
                        log.error("app.admin.operator-id 가 운영자 계정이 아니다. operatorId={}", configured);
                        return new BusinessException(ErrorCode.INVALID_PASSCODE);
                    });
        }
        return userRepository.findFirstByRoleAndDeletedAtIsNullOrderByIdAsc(UserRole.OPERATOR)
                .orElseGet(this::provisionConsoleAccount);
    }

    /**
     * 콘솔 전용 운영자 계정을 만든다. <b>소셜 신원이 고정</b>({@code uq_users_oauth_identity})이라
     * 두 번 만들어지지 않는다 — 동시 로그인이 겹치면 한쪽이 제약에 걸리고, 재시도가 찾아 쓴다.
     *
     * <p>이 계정은 앱을 쓰지 않는다. 그래서 점수 요약도 동의 이력도 만들지 않고,
     * <b>공지 팬아웃과 회원 수 집계에서도 빠진다</b>(role 로 거른다) — 운영자는 회원이 아니다.
     */
    private User provisionConsoleAccount() {
        User existing = userRepository
                .findByOauthProviderAndOauthSubject(CONSOLE_PROVIDER, CONSOLE_SUBJECT).orElse(null);
        if (existing != null) {
            // 롤만 빠진 상태로 남아 있을 수 있다(수동으로 되돌렸다든지). 이름은 건드리지 않는다.
            if (!existing.isOperator()) existing.grantRole(UserRole.OPERATOR);
            return existing;
        }

        User operator = User.create(CONSOLE_PROVIDER, CONSOLE_SUBJECT, null,
                consoleNickname(), null, List.of());
        operator.approveNickname();     // 심사 대상이 아니다 — 타인에게 노출될 자리가 없다
        operator.grantRole(UserRole.OPERATOR);

        User saved = userRepository.save(operator);
        log.warn("운영자 콘솔 계정을 새로 만들었다. operatorId={}", saved.getId());
        return saved;
    }

    /** 닉네임은 12자 제한에 UNIQUE 다. 이미 쓰이고 있으면 접미사를 붙여 피한다. */
    private String consoleNickname() {
        if (!userRepository.isNicknameTaken(CONSOLE_NICKNAME, null)) return CONSOLE_NICKNAME;
        for (int i = 0; i < 20; i++) {
            String candidate = CONSOLE_NICKNAME + (int) (Math.random() * 10_000);
            if (!userRepository.isNicknameTaken(candidate, null)) return candidate;
        }
        throw new IllegalStateException("운영자 콘솔 계정의 닉네임을 정하지 못했다");
    }

    private UUID parseOperatorId() {
        try {
            return UUID.fromString(adminProperties.operatorId());
        } catch (IllegalArgumentException e) {
            log.error("app.admin.operator-id 가 UUID 형식이 아니다.");
            throw new BusinessException(ErrorCode.INVALID_PASSCODE);
        }
    }

    private Instant expiresAt() {
        return Instant.now().plusSeconds(appProperties.jwt().accessTokenTtl());
    }

    private boolean exceeded(String clientKey) {
        Attempts current = attempts.get(clientKey);
        if (current == null) return false;
        if (Instant.now().isAfter(current.windowStart().plus(WINDOW))) {
            attempts.remove(clientKey);
            return false;
        }
        return current.count() >= MAX_ATTEMPTS;
    }

    private void fail(String clientKey) {
        attempts.compute(clientKey, (key, current) -> {
            Instant now = Instant.now();
            if (current == null || now.isAfter(current.windowStart().plus(WINDOW)))
                return new Attempts(1, now);
            return new Attempts(current.count() + 1, current.windowStart());
        });
    }

    /** 길이 차이로도 정보가 새지 않게 해시를 비교한다. */
    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(sha256(a), sha256(b));
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("진입 비밀번호 비교 실패", e);
        }
    }
}
