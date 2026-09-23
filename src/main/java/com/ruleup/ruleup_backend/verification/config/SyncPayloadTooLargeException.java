package com.ruleup.ruleup_backend.verification.config;

/**
 * sync 본문이 상한을 넘긴 순간 읽기를 끊는 신호.
 *
 * <p>본문 전체를 힙에 올린 뒤 크기를 재면 늦다 — 막으려는 것이 바로 그 메모리라서, 읽는 도중에 끊어야 한다.
 * 그래서 예외로 흐름을 자른다. 스택트레이스는 필요 없어 채우지 않는다(폭탄 요청이 몰릴 때의 비용).
 *
 * <p>파서가 이 예외를 자기 예외로 감싸 올리므로, 최종 413 매핑은 GlobalExceptionHandler 가 원인 사슬을
 * 따라가 처리한다 — DispatcherServlet 이 먼저 잡아 필터까지 올라오지 않기 때문이다.
 */
public class SyncPayloadTooLargeException extends RuntimeException {
    public SyncPayloadTooLargeException() {
        super(null, null, false, false);
    }
}
