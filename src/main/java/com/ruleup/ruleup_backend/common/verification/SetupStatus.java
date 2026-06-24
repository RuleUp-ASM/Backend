package com.ruleup.ruleup_backend.common.verification;

/**
 * 멤버 최초 진입 셋업 상태(테크스펙 v2 §4).
 *  - PENDING_SETUP : 가입했으나 권한·앵커 셋업 전 → sync 신호는 수용하되 평가 skip("권한 없는데 FAILED" 원천 차단).
 *  - READY         : 셋업 완료 → 평가 대상.
 *
 * <p>challenge·verification 공유 커널. (ChallengeMember 비정규화 컬럼이 보유)
 */
public enum SetupStatus { PENDING_SETUP, READY }
