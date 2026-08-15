package com.ruleup.ruleup_backend.profile.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 부분 수정: null인 필드는 변경하지 않음. */
@Schema(name = "UpdateProfileRequest", description = """
        프로필 부분 수정. 보낸 필드만 바뀌고, 빼거나 null 로 둔 필드는 그대로 유지된다.
        현재 값과 같은 값은 변경으로 치지 않는다.""")
public record UpdateProfileRequest(

        @Schema(description = """
                새 닉네임. 30일에 한 번만 바꿀 수 있다(거절된 닉네임을 고치는 경우는 제외).
                바꾸면 재검수에 들어가고(PENDING), 쓰던 닉네임은 1주일간 타인이 쓸 수 없다.""",
                example = "규칙왕")
        String nickname,

        @Schema(description = """
                관심 카테고리 코드 목록. 보낸 배열로 통째로 교체된다(추가가 아니다).
                0~6개이며 빈 배열이면 전부 해제된다. 선택지는 GET /api/v1/categories 참고.""",
                example = "[\"EXERCISE\",\"STUDY\"]")
        List<String> interestCategories,

        @Schema(description = """
                프로필 사진 URL 직접 지정(선택). 바꾸면 사진도 재검수 대상이 된다.
                파일 업로드는 POST /api/v1/profile/image 를 쓴다.""",
                example = "https://api.ruleup.app/files/0f7a3c1e-profile.jpg")
        String profileImageUrl) {}
