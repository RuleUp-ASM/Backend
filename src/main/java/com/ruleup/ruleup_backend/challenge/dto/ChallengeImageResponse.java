package com.ruleup.ruleup_backend.challenge.dto;

/** 3.x 챌린지 대표 이미지 업로드 응답. 반환된 imageUrl을 생성/수정 요청 body에 넣는다. */
public record ChallengeImageResponse(String imageUrl) {}