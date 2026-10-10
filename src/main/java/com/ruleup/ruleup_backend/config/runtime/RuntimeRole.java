package com.ruleup.ruleup_backend.config.runtime;

/**
 * 이 프로세스가 무엇으로 떠 있는가 — {@code app.runtime.role}.
 *
 * <p>같은 jar 를 일반 API 와 관리자 API 두 서비스로 띄운다. 역할이 정하는 것은 셋이다.
 * <ul>
 *   <li><b>어떤 엔드포인트가 등록되는가</b> — {@link RoleScopedHandlerMappingConfig}.
 *       일반 API 에는 {@code /api/v1/admin/**} 가 <b>아예 매핑되지 않는다</b>(차단이 아니라 부재)</li>
 *   <li><b>어떤 인증을 쓰는가</b> — 일반은 앱 JWT, 관리자는 Cloudflare Access JWT</li>
 *   <li><b>배경 작업을 도는가</b> — 배치·SQS 컨슈머·기동 워밍업은 일반 API 만 돈다.
 *       관리자 서비스가 같이 돌면 공지 팬아웃·알림 발송이 두 번 나간다</li>
 * </ul>
 *
 * <p>{@link #MIGRATE} 는 스키마 마이그레이션만 돌고 끝나는 일회성 태스크다. 마이그레이션 전용 DB 계정
 * (DDL 권한)은 이 역할에만 주고, 상시 서비스(api·admin)의 계정에서는 DDL 을 뺀다.
 *
 * <p>{@link #ALL} 은 로컬·시험 전용이다. 배포 프로필(stg·prod)에서 ALL 로 뜨면
 * {@link RuntimeRoleGuard} 가 기동을 막는다 — 관리자 경로가 공개 ALB 뒤에 다시 열리는 길이기 때문이다.
 */
public enum RuntimeRole {
    API,
    ADMIN,
    MIGRATE,
    ALL;

    public boolean servesPublicApi() {
        return this == API || this == ALL;
    }

    public boolean servesAdminApi() {
        return this == ADMIN || this == ALL;
    }

    /** 배치·컨슈머·기동 워밍업처럼 「서비스당 한 벌」이어야 하는 일을 이 프로세스가 맡는가. */
    public boolean runsBackgroundWork() {
        return this == API || this == ALL;
    }
}
