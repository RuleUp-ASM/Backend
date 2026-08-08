package com.ruleup.ruleup_backend.user;

import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.user.domain.NicknameReleaseLock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 닉네임 변경 시 이전 닉네임 1주일 잠금 (회원 정책 §3 — 사칭 방지).
 *
 * <p>잠기는 값은 <b>사용자가 직접 고른 이전 닉네임</b>뿐이다. 임시 닉네임(UUID 8자)은
 * 애초에 중복이 허용되는 값이라 잠글 대상이 아니고, 탈퇴는 정책상 잠금 사유가 아니다
 * (회원 정책 §6은 복원 시 타인이 그 닉네임을 선점했을 수 있음을 전제한다).
 *
 * <p>잠금은 "타인" 기준이다 — 버린 본인은 기간 중에도 되돌릴 수 있다.
 */
@Service
@RequiredArgsConstructor
public class NicknameReleaseLockService {

    private final NicknameReleaseLockRepository lockRepository;

    /** 닉네임을 버릴 때 호출 — 같은 닉네임이 이미 잠겨 있으면 새 기준으로 다시 7일을 센다. */
    @Transactional
    public void release(String nickname, UUID releasedBy) {
        if (nickname == null || nickname.isBlank()) return;
        Instant now = Instant.now();
        lockRepository.findById(nickname).ifPresentOrElse(
                lock -> lock.relock(releasedBy, now),
                () -> lockRepository.save(NicknameReleaseLock.of(nickname, releasedBy, now)));
    }

    /**
     * candidateId 에게 이 닉네임이 잠겨 있으면 해제 시각을, 아니면 비어 있음을 돌려준다.
     * 신규 가입처럼 주체가 없으면 candidateId=null (누구의 본인 예외도 아니다).
     */
    @Transactional(readOnly = true)
    public Optional<Instant> lockedUntilFor(String nickname, UUID candidateId) {
        Instant now = Instant.now();
        return lockRepository.findById(nickname)
                .filter(lock -> lock.blocks(candidateId, now))
                .map(NicknameReleaseLock::getLockedUntil);
    }

    /** 가입·변경 제출 시점의 서버 검증 — 잠겨 있으면 409 NICKNAME_RECENTLY_RELEASED. */
    @Transactional(readOnly = true)
    public void requireNotLocked(String nickname, UUID candidateId) {
        if (lockedUntilFor(nickname, candidateId).isPresent())
            throw new BusinessException(ErrorCode.NICKNAME_RECENTLY_RELEASED);
    }

    /**
     * 닉네임이 정당하게 다시 점유됐을 때 잠금 행을 지운다(본인 되돌리기 등).
     * 남겨두면 그 사용자가 다시 버릴 때까지 만료만 기다리는 죽은 행이 된다.
     */
    @Transactional
    public void clear(String nickname) {
        if (nickname == null || nickname.isBlank()) return;
        lockRepository.deleteById(nickname);
    }
}
