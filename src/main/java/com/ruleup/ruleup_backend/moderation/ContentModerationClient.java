package com.ruleup.ruleup_backend.moderation;

/**
 * 닉네임/프로필 사진 검수 클라이언트.
 * 현재 구현은 Solar(Upstage), 추후 Gemini(멀티모달)로 교체 예정.
 * 어떤 이유로든 검수가 불가하면 예외 대신 {@link ModerationResult#UNAVAILABLE}을 돌려준다(검수 보류).
 */
public interface ContentModerationClient {

    /** 닉네임 텍스트 검수. */
    ModerationResult moderateNickname(String nickname);

    /**
     * 프로필 사진 검수. 텍스트 전용 모델(Solar)에서는 이미지를 볼 수 없어 UNAVAILABLE(보류)을 반환한다.
     * 멀티모달(Gemini)로 교체되면 실제 이미지 검수를 수행한다.
     */
    ModerationResult moderateImage(String imageUrl);
}
