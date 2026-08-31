package com.ruleup.ruleup_backend.profile.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 프로필 편집(PATCH /users/me/profile) 요청. null 인 필드는 그대로 유지된다.
 *
 * <p>생일·성별은 가입 후 수정 불가 항목이라 이 API 에 없다. 사진 <b>등록</b>도 여기가 아니라
 * {@code POST /users/me/profile-image}(업로드+등록) 소관이고, 여기서는 삭제만 받는다.
 */
@Schema(name = "UpdateProfileRequest", description = """
        닉네임·관심 분야·사진 삭제. 닉네임과 사진은 통합 1개월 잠금이 걸리고,
        관심 분야는 잠금 예외라 언제든 바꿀 수 있다.""")
public record UpdateProfileRequest(

        @Schema(description = """
                변경할 닉네임. 저장하면 재심사(PENDING)에 들어가고, 그 시점부터 닉네임·사진이
                함께 1개월 잠긴다. 내려놓은 이전 닉네임은 1주간 다른 사람이 쓸 수 없다.""",
                example = "새벽러너")
        String nickname,

        @Schema(description = "관심 분야 0~6개. 보낸 배열로 통째로 교체된다(추가가 아니다).",
                example = "[\"EXERCISE\",\"WAKE_SLEEP\",\"READING\"]")
        List<String> interestCategories,

        @Schema(description = "true 면 사진을 지워 기본 프로필로 되돌린다.", example = "false")
        Boolean removeProfileImage) {}
