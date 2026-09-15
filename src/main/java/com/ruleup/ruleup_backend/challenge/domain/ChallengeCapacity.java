package com.ruleup.ruleup_backend.challenge.domain;

import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;

import java.util.List;
import java.util.Set;

/**
 * 그룹 챌린지의 정원 — <b>고른 선택지 중 하나</b>이지 자유 입력값이 아니다.
 *
 * <h4>왜 자유 입력이 아닌가</h4>
 * 정원은 가입 경로에서 방 행을 배타로 잡고 인원을 세는 값이다. 1~300 아무 숫자나 받으면
 * 사실상 같은 크기의 방이 300가지로 흩어지고, 「무제한을 흉내 내려고 큰 수를 고른 방」이
 * 계속 락과 COUNT 를 지불한다. 선택지를 몇 개로 묶으면 방 크기가 그 몇 개로 수렴한다.
 *
 * <h4>왜 하필 이 다섯인가</h4>
 * 소규모(5) · 보통(30) · 대형(100) · 상한(300) · 무제한. 사이 값을 늘리면 고르는 사람에게는
 * 자유 입력과 다를 바 없어지고, 줄이면 방 성격을 표현할 수 없다.
 *
 * <p><b>무제한은 {@code null} 이다.</b> 0 이나 큰 수가 아니다 — 가입 경로가 "정원이 있으면
 * 센다"로 갈리므로, 무제한을 숫자로 표현하면 무제한 방도 매번 COUNT 를 지불하게 된다.
 *
 * <p>솔로는 이 목록과 무관하게 <b>1 고정</b>이고 화면에 정원 개념을 노출하지 않는다.
 */
public final class ChallengeCapacity {

    /** 고를 수 있는 값. 이 집합 밖은 무엇이든 400 이다 — 사이 값도, 범위 안이라도 마찬가지다. */
    public static final Set<Integer> CHOICES = Set.of(5, 30, 100, 300);

    /** 사람이 읽는 순서. 에러 문구·문서에 쓴다(집합은 순서가 없다). */
    public static final List<Integer> ORDERED = List.of(5, 30, 100, 300);

    /** 솔로 방의 정원. 선택지가 아니라 고정값이다. */
    public static final int SOLO = 1;

    /**
     * 초안이 들고 오는 기본 정원.
     *
     * <p>예전 기본값은 50 이었는데 <b>선택지에 50 이 없다.</b> 기본값이 고를 수 없는 값이면,
     * 초안을 그대로 확정하는 가장 흔한 경로가 400 으로 떨어진다 — 기본값은 반드시
     * {@link #CHOICES} 안에 있어야 한다. 남은 값 중 50 에 가장 가까운 30 을 쓴다.
     */
    public static final int DEFAULT = 30;

    private ChallengeCapacity() {}

    /**
     * 그룹 정원 검증. {@code null} 은 무제한이라 그대로 통과한다.
     *
     * @return 저장할 값 — 무제한이면 {@code null}
     * @throws BusinessException 선택지 밖 값이면 {@code CAPACITY_OUT_OF_RANGE}
     */
    public static Integer validateGroup(Integer capacity) {
        if (capacity == null) return null;                 // 무제한
        if (!CHOICES.contains(capacity))
            throw new BusinessException(ErrorCode.CAPACITY_OUT_OF_RANGE);
        return capacity;
    }
}
