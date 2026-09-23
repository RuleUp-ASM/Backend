package com.ruleup.ruleup_backend.agreement.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 동의 상태 조회·제출 계약 (온보딩 테크 스펙 5-2 #11·#12). */
public final class AgreementDtos {

    private AgreementDtos() {}

    @Schema(name = "AgreementSubmitRequest", description = """
            동의 제출·철회. 배열로 받아 여러 항목을 한 번에 처리하며 전체가 한 트랜잭션이다 —
            개정 약관이 동시에 여러 개 나올 수 있기 때문이다.""")
    public record SubmitRequest(

            @Schema(description = "갱신할 항목 목록. 비어 있으면 400 INVALID_REQUEST.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            List<Item> agreements) {

        @Schema(name = "AgreementSubmitItem")
        public record Item(

                @Schema(description = """
                        TOS · PRIVACY · LOCATION · MARKETING · EVENT · LOCATION_INFO · HEALTH_INFO 7종.
                        구 NIGHT_PUSH 는 폐지됐다.""",
                        example = "LOCATION_INFO", requiredMode = Schema.RequiredMode.REQUIRED)
                String type,

                @Schema(description = "true 동의 · false 철회", requiredMode = Schema.RequiredMode.REQUIRED)
                Boolean agreed,

                @Schema(description = """
                        동의하는 약관 버전. 서버의 현재 유효 버전과 다르면 400 AGREEMENT_VERSION_MISMATCH —
                        구 버전을 동의본으로 남기면 입증이 깨진다.""",
                        example = "1.0", requiredMode = Schema.RequiredMode.REQUIRED)
                String version) {}
    }

    @Schema(name = "AgreementStatusResponse", description = """
            동의 현재 상태. 조회는 7종 전부를, 제출 응답은 갱신된 항목만 담는다.""")
    public record StatusResponse(

            @Schema(description = "동의 항목별 현재 상태", requiredMode = Schema.RequiredMode.REQUIRED)
            List<Item> agreements,

            @Schema(description = """
                    현재 유효 버전과 다른 **필수 약관** 목록. 비어 있으면 재동의 화면을 띄우지 않는다.
                    클라이언트가 인트로의 termsVersions 와 직접 비교할 필요는 없다.""",
                    example = "[\"TOS\"]", requiredMode = Schema.RequiredMode.REQUIRED)
            List<String> reconsentRequired) {

        @Schema(name = "AgreementStatusItem")
        public record Item(

                @Schema(description = "동의 항목", example = "TOS") String type,

                @Schema(description = """
                        **가입 시** 필수 여부. 개별 동의 2종은 가입 필수가 아니라 false 지만,
                        해당 인증 수단을 쓰려면 필수다.""", example = "true")
                boolean required,

                @Schema(description = "현재 동의 여부", example = "true") boolean agreed,

                @Schema(description = """
                        동의한 버전. null 이면 **한 번도 동의한 적 없음**이다.
                        agreed=false 인데 version 이 있으면 동의 후 철회한 것이다.""", example = "1.0")
                String version,

                @Schema(description = "그 상태가 된 시각(ISO-8601). 미동의면 null.",
                        example = "2026-07-01T02:11:00Z")
                String agreedAt) {}
    }
}
