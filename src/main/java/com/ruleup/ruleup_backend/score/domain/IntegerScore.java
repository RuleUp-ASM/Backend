package com.ruleup.ruleup_backend.score.domain;

/**
 * 정수 산식 — 점수 및 티어 정책 §4.5.
 *
 * <p>주간 배점 {@code W} 를 주간 목표 횟수 {@code N} 으로 나누면 소수가 나온다(골드 주 7회 = 0.857…).
 * 그 소수를 저장하지 않는다. 대신 <b>k번째 확정까지의 반영 누계</b>를 정수로 직접 계산한다.
 *
 * <pre>f(k) = ⌊(2·W·k + N) ÷ (2·N)⌋</pre>
 *
 * <p>이 식은 {@code W×k÷N} 의 사사오입(0.5 이상 올림)과 동치이면서 나눗셈까지 전부 정수 연산이다.
 * 언어 기본 {@code round()} 는 은행가 반올림일 수 있고 {@code double} 은 재현성이 없어 둘 다 쓰지 않는다.
 *
 * <p>이 설계가 주는 성질이 셋이다.
 * <ul>
 *   <li><b>멱등</b> — 반영 누계가 카운트만의 함수라 같은 이벤트를 두 번 처리해도 결과가 같다.
 *       이번에 반영할 값은 언제나 {@code f(현재 카운트) − f(직전 카운트)} 다.</li>
 *   <li><b>소급 정정이 쉽다</b> — 이의가 인용되면 카운트를 고쳐 다시 계산하고 차이만 반영하면 된다.</li>
 *   <li><b>마감 보정이 없다</b> — {@code f(N) = W} 가 항상 성립해서 주간 목표를 다 채웠을 때
 *       누계가 정책 표의 주간 총 배점과 정확히 일치한다. 잔여 오차를 정산할 절차 자체가 필요 없다.</li>
 * </ul>
 *
 * <p>오버플로 여유: {@code 2·W·k} 의 최댓값은 루비 미달축 {@code 2 × 38 × 7 = 532} 라 int 안에서 안전하다.
 */
public final class IntegerScore {

    private IntegerScore() {}

    /**
     * @param weeklyPoints 해당 축의 주간 배점 W — 성공축·미달축 모두 <b>양수</b>로 넣고 부호는 호출부가 붙인다
     * @param targetCount  주간 목표 횟수 N (1~7)
     * @param count        확정 카운트 k
     * @return k번째 확정까지의 반영 누계
     */
    public static int f(int weeklyPoints, int targetCount, int count) {
        if (targetCount <= 0) throw new IllegalArgumentException("주간 목표 횟수는 1 이상이어야 한다: " + targetCount);
        if (count <= 0) return 0;
        return (2 * weeklyPoints * count + targetCount) / (2 * targetCount);
    }
}
