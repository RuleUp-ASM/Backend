package com.ruleup.ruleup_backend.devlog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 요청 로그의 값 가리기 — <b>무엇을 가리고 무엇을 남기는지</b>가 이 필터의 전부다.
 *
 * <p>너무 적게 가리면 자격증명이 콘솔에 남고, 너무 많이 가리면 로그가 쓸모없어진다.
 * 두 방향 모두 테스트로 묶어 둔다.
 */
class RequestLogMaskingTest {

    private String mask(String body) throws Exception {
        RequestLogFilter filter = new RequestLogFilter(null, true, 10_000);
        Method m = RequestLogFilter.class.getDeclaredMethod("mask", String.class);
        m.setAccessible(true);
        return (String) m.invoke(filter, body);
    }

    @Nested
    @DisplayName("가린다")
    class Hidden {

        @Test
        @DisplayName("운영자 콘솔 로그인의 passcode — 이름에 password 가 없어 그대로 남던 값이다")
        void adminPasscode() throws Exception {
            assertThat(mask("{\"passcode\":\"hunter2\"}")).isEqualTo("{\"passcode\":\"***\"}");
            assertThat(mask("{\"password\":\"hunter2\"}")).isEqualTo("{\"password\":\"***\"}");
        }

        @Test
        @DisplayName("소셜 로그인의 인가 코드와 code_verifier — 둘 다 일회용 자격증명이다")
        void oauthCredentials() throws Exception {
            assertThat(mask("{\"code\":\"abc.def\"}")).isEqualTo("{\"code\":\"***\"}");
            assertThat(mask("{\"codeVerifier\":\"abc\"}")).isEqualTo("{\"codeVerifier\":\"***\"}");
            assertThat(mask("{\"code_verifier\":\"abc\"}")).isEqualTo("{\"code_verifier\":\"***\"}");
        }

        @Test
        @DisplayName("값 안의 이스케이프된 따옴표 뒤쪽까지 가린다")
        void escapedQuoteInsideValue() throws Exception {
            assertThat(mask("{\"passcode\":\"ab\\\"cd\",\"x\":1}"))
                    .isEqualTo("{\"passcode\":\"***\",\"x\":1}");
        }

        @Test
        @DisplayName("상한에 걸려 잘린 본문이어도 비밀번호 앞부분이 남지 않는다")
        void truncatedBody() throws Exception {
            RequestLogFilter filter = new RequestLogFilter(null, true, 20);
            Method m = RequestLogFilter.class.getDeclaredMethod("mask", String.class);
            m.setAccessible(true);
            String out = (String) m.invoke(filter, "{\"note\":\"aaaa\",\"passcode\":\"hunter2hunter2\"}");
            assertThat(out).doesNotContain("hunter");
            // 캐싱 래퍼가 이미 잘라 닫는 따옴표가 없는 본문
            assertThat(mask("{\"passcode\":\"hunter2hun")).isEqualTo("{\"passcode\":\"***\"");
        }

        @Test
        @DisplayName("이름 안에 들어 있기만 해도 가린다")
        void substringMatch() throws Exception {
            assertThat(mask("{\"refreshToken\":\"x\",\"clientSecret\":\"y\"}"))
                    .isEqualTo("{\"refreshToken\":\"***\",\"clientSecret\":\"***\"}");
        }
    }

    @Nested
    @DisplayName("남긴다")
    class Kept {

        @Test
        @DisplayName("code 로 끝나는 이름은 자격증명이 아니다 — 여기까지 가리면 로그를 읽을 수 없다")
        void codeSuffixesStay() throws Exception {
            String body = "{\"errorCode\":\"JOIN_BLOCKED\",\"categoryCode\":\"EXERCISE\","
                    + "\"reasonCode\":\"NO_SIGNAL_RECEIVED\"}";
            assertThat(mask(body)).isEqualTo(body);
        }

        @Test
        @DisplayName("평범한 본문은 그대로 둔다")
        void ordinaryFieldsStay() throws Exception {
            String body = "{\"title\":\"아침 러닝\",\"weeklyCount\":7}";
            assertThat(mask(body)).isEqualTo(body);
        }
    }
}
