package com.ruleup.ruleup_backend.security;

/** JWT 종류 (토큰의 "type" 클레임에 담겨 서로를 구분). */
public enum TokenType {
    ACCESS, REFRESH, SIGNUP
}