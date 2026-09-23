package com.ruleup.ruleup_backend.devtoken;

/**
 * 개발용 토큰 발급 요청·응답. 스웨거에 노출하지 않으므로 {@code @Schema} 를 달지 않는다 —
 * 문서에 나오지 않아야 하는 API 에 문서용 애노테이션을 붙이면 의도가 흐려진다.
 */
public final class DevTokenDtos {

    private DevTokenDtos() {}

    /**
     * 전부 선택이다. 비우면 온보딩을 마친 새 테스트 계정을 만든다.
     *
     * @param userId      있으면 해당 계정으로 발급하고 나머지 필드는 무시한다
     * @param nickname    생략 시 {@code test_} + 랜덤. 중복이면 서버가 서픽스를 붙여 피한다
     * @param status      ACTIVE / SUSPENDED / WITHDRAWN. SUSPENDED 면 sanction 을 함께 받는다
     * @param sanction    제재 게이트 재현용
     * @param tier        기본 BRONZE. 최소 입장 티어 분기 재현용
     * @param score       기본 10. 0~2,000 범위 밖이면 400
     * @param agreements  기본 true. false 면 미동의 상태로 두어 재동의 분기를 재현한다
     */
    public record Request(String userId, String nickname, String status, Sanction sanction,
                          String tier, Integer score, Boolean agreements) {

        /** @param endsAtAfterDays 해제일까지 남은 일수. null 이면 영구 제재 */
        public record Sanction(String type, String featureCode, Integer endsAtAfterDays) {}
    }

    public record Response(String accessToken, String refreshToken, long expiresIn,
                           boolean created, User user) {

        public record User(String userId, String nickname, String status,
                           String tier, String displayTier, long score) {}
    }
}
