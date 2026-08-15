package com.ruleup.ruleup_backend.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** POST /api/v1/nicknames/check 요청 바디 (스펙 4.6). */
@Schema(name = "NicknameCheckRequest", description = "닉네임 검사 요청")
public record NicknameCheckRequest(

        @Schema(description = """
                검사할 닉네임. 규칙은 2~12자, 한글·영문·숫자만(공백·특수문자 불가).
                자음만 나열(ㄱㄱㄱ)은 허용하지만 모음만 나열(ㅏㅏㅏ)은 불허한다.
                규칙 위반이어도 400 이 아니라 200 + valid:false 로 내려간다.""",
                example = "규칙왕", requiredMode = Schema.RequiredMode.REQUIRED)
        String nickname) {}
