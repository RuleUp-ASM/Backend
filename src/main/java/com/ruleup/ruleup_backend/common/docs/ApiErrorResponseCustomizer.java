package com.ruleup.ruleup_backend.common.docs;

import com.ruleup.ruleup_backend.common.error.ErrorCode;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@link ApiErrorCodes} 로 선언한 에러 코드를 Swagger 응답으로 펼친다.
 *
 * <p>같은 HTTP 상태코드를 쓰는 코드가 여러 개인 게 정상이라(400 하나에 LOGIN_FAILED·
 * INVALID_DEVICE_INFO·BIRTHDATE_UNDERAGE …) 상태코드 하나당 응답 1개 + 코드별 Example 여러 개로 만든다.
 * 문서를 보는 쪽은 "400이면 무엇을 확인해야 하는지"를 Examples 드롭다운에서 바로 고를 수 있다.
 *
 * <p>본문 형태는 실제 {@code GlobalExceptionHandler} 가 내려주는 공통 봉투와 같다.
 * <pre>
 * { "success": false, "data": null, "error": { "code": "...", "message": "..." } }
 * </pre>
 */
@Component
public class ApiErrorResponseCustomizer implements OperationCustomizer {

    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        ApiErrorCodes declared = handlerMethod.getMethodAnnotation(ApiErrorCodes.class);
        if (declared == null || declared.value().length == 0) {
            return operation;
        }

        ApiResponses responses = (operation.getResponses() != null) ? operation.getResponses() : new ApiResponses();
        groupByStatus(declared.value()).forEach((status, codes) ->
                responses.addApiResponse(String.valueOf(status.value()), errorResponse(status, codes)));
        operation.setResponses(responses);
        return operation;
    }

    /** 상태코드 오름차순(400 → 401 → 403 …)으로 묶는다. 같은 상태 안에서는 선언 순서를 유지한다. */
    private Map<HttpStatus, List<ErrorCode>> groupByStatus(ErrorCode[] codes) {
        Map<HttpStatus, List<ErrorCode>> grouped = new TreeMap<>(Comparator.comparingInt(HttpStatus::value));
        for (ErrorCode code : codes) {
            grouped.computeIfAbsent(code.getStatus(), s -> new ArrayList<>()).add(code);
        }
        return grouped;
    }

    private ApiResponse errorResponse(HttpStatus status, List<ErrorCode> codes) {
        MediaType json = new MediaType().schema(errorEnvelopeSchema());
        for (ErrorCode code : codes) {
            json.addExamples(code.name(), example(code));
        }
        return new ApiResponse()
                .description(describe(status, codes))
                .content(new Content().addMediaType(org.springframework.http.MediaType.APPLICATION_JSON_VALUE, json));
    }

    /** "400 Bad Request" + 코드별 한 줄 설명(마크다운 목록 — Swagger UI가 렌더링한다). */
    private String describe(HttpStatus status, List<ErrorCode> codes) {
        StringBuilder sb = new StringBuilder(status.value() + " " + status.getReasonPhrase());
        for (ErrorCode code : codes) {
            sb.append("\n\n- `").append(code.name()).append("` — ").append(code.getMessage());
        }
        return sb.toString();
    }

    private Example example(ErrorCode code) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code.name());
        error.put("message", code.getMessage());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("data", null);
        body.put("error", error);

        return new Example().summary(code.name()).description(code.getMessage()).value(body);
    }

    /** 공통 에러 봉투 스키마 — 성공 응답의 {@code ApiResponse<T>} 와 같은 3필드 구조다. */
    private Schema<?> errorEnvelopeSchema() {
        Schema<?> error = new ObjectSchema()
                .description("에러 상세 (성공 시 null)")
                .addProperty("code", new StringSchema()
                        .description("에러 코드 — 클라이언트 분기 키. 상태코드만으로는 원인을 구분할 수 없다."))
                .addProperty("message", new StringSchema()
                        .description("사용자에게 그대로 노출해도 되는 안내 문구"))
                .addProperty("reason", new StringSchema()
                        .description("세부 사유 — 해당 코드에만 실린다. 없으면 필드 자체가 생략된다."));

        return new ObjectSchema()
                .description("공통 응답 봉투 (실패)")
                .addProperty("success", new BooleanSchema().description("실패 응답은 항상 false"))
                .addProperty("data", new ObjectSchema().description("실패 응답은 항상 null"))
                .addProperty("error", error);
    }
}
