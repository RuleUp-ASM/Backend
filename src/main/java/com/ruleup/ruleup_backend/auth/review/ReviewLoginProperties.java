package com.ruleup.ruleup_backend.auth.review;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 심사 코드 로그인 설정 (심사 계정 테크 스펙 5-3). 값은 Secrets Manager 에서 환경변수로 들어온다.
 *
 * <p>검증·해석은 {@link ReviewAccountPolicy} 가 기동 때 한다 — 여기서는 받은 그대로 둔다.
 * 종료 스케줄러 주기({@code terminator-interval})는 {@link ReviewSessionTerminator} 가 직접 읽는다.
 *
 * @param enabled   긴급 차단 스위치. false 면 만료 전이어도 꺼진다
 * @param round     심사 회차 {@code yyyyMMdd}(시드 실행일, KST) + 순번 2자리. 시드 생성 모드가 발급한다
 * @param expiresAt 자동 종료 시각(ISO-8601 오프셋 포함). 비어 있으면 꺼진다
 * @param codes     {@link ReviewLoginCode} 의 JSON 배열
 */
@ConfigurationProperties(prefix = "app.review-login")
public record ReviewLoginProperties(boolean enabled, String round, String expiresAt, String codes) {}
