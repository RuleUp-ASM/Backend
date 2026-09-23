package com.ruleup.ruleup_backend.invitation;

import com.ruleup.ruleup_backend.invitation.domain.InviteCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * 초대 코드의 <b>쓰기 한 번</b>과 <b>다시 읽기</b>를 각각 자기 트랜잭션에서 한다.
 *
 * <h4>왜 따로 떼어 냈나</h4>
 * 유일 제약 위반은 JPA 가 영속성 컨텍스트를 무효로 만들고 진행 중인 트랜잭션에 rollback-only 를
 * 새긴다. 그래서 <b>같은 트랜잭션 안에서 예외를 잡아</b> 「먼저 만들어진 코드를 대신 돌려주는」
 * 복구는 성립하지 않는다 — 조회까지는 되지만 커밋이 {@code UnexpectedRollbackException} 으로
 * 끝나 500 이 된다. 실패할 수 있는 쓰기를 자기 트랜잭션에 가둬야 부르는 쪽이 살아남는다.
 *
 * <p>여기서 예외를 <b>잡지 않는</b> 것도 같은 이유다. 잡아서 돌려주면 이 트랜잭션의 커밋이
 * 다시 터진다 — 문제를 한 층 안으로 옮길 뿐이다.
 *
 * <p>다시 읽는 쪽도 새 트랜잭션이어야 한다. REPEATABLE READ 에서는 부르는 쪽이 이미 「없음」을
 * 읽은 스냅샷에 묶여 있어, 경합 상대가 그 사이 커밋한 행이 같은 트랜잭션에서는 끝내 보이지 않는다.
 */
@Component
@RequiredArgsConstructor
class InviteCodeIssuer {

    private final InviteCodeRepository inviteCodeRepository;

    /** 한 번 시도한다. 유일 제약에 걸리면 그대로 던진다 — 판단은 부르는 쪽이 한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public InviteCode issue(UUID userId, String code) {
        return inviteCodeRepository.saveAndFlush(InviteCode.of(userId, code));
    }

    /** 방금 커밋된 것까지 보이는 조회. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<InviteCode> findFresh(UUID userId) {
        return inviteCodeRepository.findByUserId(userId);
    }

    /** 코드 문자열이 이미 쓰이고 있는지 — 재시도 판단용이라 최신 값을 본다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean codeTaken(String code) {
        return inviteCodeRepository.existsByCode(code);
    }
}
