package com.ruleup.ruleup_backend.moderation;

/**
 * 닉네임/프로필 사진 검수 클라이언트.
 * 현재 구현은 Gemini(멀티모달)라 닉네임 텍스트·프로필 사진 모두 실제 검수가 가능하다.
 * 어떤 이유로든 검수가 불가하면 예외 대신 {@link ModerationResult#UNAVAILABLE}을 돌려준다(검수 보류).
 */
public interface ContentModerationClient {

    /** 닉네임 텍스트 검수. */
    ModerationResult moderateNickname(String nickname);

    /** 프로필 사진 검수(멀티모달, URL). 이미지를 가져오지 못하면 UNAVAILABLE(보류). */
    ModerationResult moderateImage(String imageUrl);

    /**
     * 이미지 바이트 동기 검수(업로드 핫패스, §9 SafeSearch 게이트).
     * 명백 위반이면 REJECTED, 문제없으면 APPROVED, 검수 불가(AI 미설정/실패)면 UNAVAILABLE(=업로드 허용).
     */
    ModerationResult moderateImageBytes(byte[] bytes, String mimeType);
}
