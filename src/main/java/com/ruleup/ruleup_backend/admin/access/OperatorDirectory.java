package com.ruleup.ruleup_backend.admin.access;

import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.OAuthProvider;
import com.ruleup.ruleup_backend.user.domain.User;
import com.ruleup.ruleup_backend.user.domain.UserRole;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Access 로 확인된 운영자 이메일 → 운영자 계정.
 *
 * <h4>사람마다 계정 하나</h4>
 * 비밀번호 진입은 운영자 전원이 한 계정을 나눠 써서 감사 로그가 「누가」를 답하지 못했다.
 * 이제 신원이 이메일로 오므로 <b>이메일마다 운영자 계정을 하나씩</b> 둔다. 그러면 제재·장애 구제·
 * 감사 로그의 {@code operator_id} 가 그대로 사람을 가리킨다 — 스키마 변경 없이.
 *
 * <p>계정은 첫 접속 때 만든다(콘솔 계정과 같은 방식, {@code AdminAuthService}). 소셜 신원을
 * {@code cf-access:<이메일>} 로 고정하므로 두 번 만들어지지 않는다. 앱 회원 계정과는 섞이지 않는다 —
 * 같은 이메일의 회원이 있어도 별개 행이고, 회원 이메일 칸도 비워 둔다.
 *
 * <p>허용 목록에서 빠진 이메일은 계정이 남아 있어도 들어오지 못한다(필터가 먼저 거른다).
 */
@Slf4j
public class OperatorDirectory {

    static final OAuthProvider PROVIDER = OAuthProvider.KAKAO;
    static final String SUBJECT_PREFIX = "cf-access:";
    private static final String NICKNAME = "운영자";

    private final UserRepository userRepository;
    private final TransactionTemplate tx;

    public OperatorDirectory(UserRepository userRepository, TransactionTemplate tx) {
        this.userRepository = userRepository;
        this.tx = tx;
    }

    /** 운영자 계정 id. 탈퇴 처리된 계정이면 비어 있다 — 되살리지 않는다. */
    public Optional<UUID> resolve(String email) {
        String subject = SUBJECT_PREFIX + email;
        try {
            return tx.execute(s -> findOrCreate(subject));
        } catch (DataIntegrityViolationException race) {
            // 같은 사람이 동시에 두 요청을 보내 둘 다 만들려 한 경우 — 먼저 만든 쪽을 쓴다.
            return tx.execute(s -> userRepository.findByOauthProviderAndOauthSubject(PROVIDER, subject)
                    .filter(u -> !u.isWithdrawn()).map(User::getId));
        }
    }

    private Optional<UUID> findOrCreate(String subject) {
        Optional<User> existing = userRepository.findByOauthProviderAndOauthSubject(PROVIDER, subject);
        if (existing.isPresent()) {
            User u = existing.get();
            if (u.isWithdrawn()) return Optional.empty();
            if (!u.isOperator()) u.grantRole(UserRole.OPERATOR);
            return Optional.of(u.getId());
        }
        User operator = User.create(PROVIDER, subject, null, nickname(), null, List.of());
        operator.approveNickname();     // 타인에게 노출될 자리가 없다
        operator.grantRole(UserRole.OPERATOR);
        User saved = userRepository.saveAndFlush(operator);
        log.warn("Access 운영자 계정을 새로 만들었다. operatorId={}", saved.getId());
        return Optional.of(saved.getId());
    }

    /** 닉네임은 12자 제한에 UNIQUE 다. */
    private String nickname() {
        for (int i = 0; i < 20; i++) {
            String candidate = NICKNAME + (int) (Math.random() * 1_000_000);
            if (!userRepository.isNicknameTaken(candidate, null)) return candidate;
        }
        throw new IllegalStateException("운영자 계정의 닉네임을 정하지 못했다");
    }
}
