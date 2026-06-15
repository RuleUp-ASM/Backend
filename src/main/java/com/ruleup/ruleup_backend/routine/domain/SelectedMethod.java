package com.ruleup.ruleup_backend.routine.domain;

/**
 * 사용자가 고른(또는 추천된) 인증 방식.
 *  - AUTO   : 폰/헬스커넥트/외부서비스 신호로 자동 인증 (템플릿에 자동 옵션이 있을 때만)
 *  - MANUAL : 사진 또는 그룹 체크로 직접 인증 (항상 가능)
 */
public enum SelectedMethod {
    AUTO, MANUAL
}
