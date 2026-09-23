package com.ruleup.ruleup_backend.common.docs;

import com.ruleup.ruleup_backend.common.error.ErrorCode;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 이 엔드포인트가 내려줄 수 있는 에러 코드 목록.
 *
 * <p>Swagger 문서에 응답 예시를 손으로 적으면 {@link ErrorCode} 가 바뀔 때마다 문서가 조용히 낡는다.
 * 그래서 "코드 목록"만 선언하고, 상태코드·메시지·응답 본문은
 * {@link ApiErrorResponseCustomizer} 가 enum 에서 직접 읽어 만든다.
 * → 메시지를 고치면 문서가 같이 고쳐진다.
 *
 * <p>선언 순서가 문서의 예시 순서다. 같은 HTTP 상태코드끼리는 하나의 응답으로 묶이고,
 * 코드별 본문은 Examples 드롭다운으로 갈라진다(상태코드만으로 원인을 구분할 수 없기 때문).
 *
 * <pre>
 * &#64;ApiErrorCodes({ErrorCode.LOGIN_FAILED, ErrorCode.ACCOUNT_BANNED})
 * </pre>
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ApiErrorCodes {

    ErrorCode[] value();
}
