package com.ruleup.ruleup_backend.notification.consumer;

import com.google.firebase.messaging.MessagingErrorCode;
import com.ruleup.ruleup_backend.notification.domain.NotificationTab;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import com.ruleup.ruleup_backend.notification.queue.NotificationMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FCM 묶음 응답 접기 — <b>한 번의 {@code sendEach} 결과를 알림 단위로 되돌리는 규칙</b>이다.
 *
 * <p>묶음 전송으로 바꾸면서 응답과 알림이 1:1 이 아니게 됐다. 한 알림이 토큰 수만큼 행을 내고,
 * 여러 알림의 행이 한 응답에 섞여 돌아온다. 그래서 <b>되짚는 색인이 어긋나면</b> 남의 기기를
 * 비활성화하거나(죽은 토큰 오귀속) 성공한 알림을 실패로 적는다 — 둘 다 조용히 아프다.
 *
 * <p>스프링도 목도 쓰지 않는다. {@code SendResponse} 가 final 이고 생성자가 패키지 전용이라
 * 애초에 만들 수 없고, 규칙 자체는 Firebase 타입과 무관하기 때문이다. 전송기와 <b>같은 패키지</b>에
 * 두는 이유도 그것이다 — 접기 규칙을 검증하려고 프로덕션 API 를 public 으로 넓히지 않는다.
 */
class FcmBulkFoldTest {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private static PushRequest request(String token, String... more) {
        List<String> tokens = new ArrayList<>();
        tokens.add(token);
        tokens.addAll(List.of(more));
        return new PushRequest(message(), tokens);
    }

    private static PushRequest requestWithoutToken() {
        return new PushRequest(message(), List.of());
    }

    private static NotificationMessage message() {
        NotificationType type = NotificationType.ACCOUNT_SANCTION;
        return new NotificationMessage(UUID.randomUUID(), USER, type.name(), type.toggleGroup(),
                null, NotificationTab.NOTIFICATION, "제목", "본문", "ruleup://me/sanctions",
                type.suppressKey(Map.of()));
    }

    /** 보낸 순서 그대로의 색인 — 프로덕션이 (요청, 토큰) 쌍을 펴는 방식과 같다. */
    private static Fold flatten(List<PushRequest> requests) {
        List<PushRequest> owner = new ArrayList<>();
        List<String> tokenOf = new ArrayList<>();
        for (PushRequest request : requests) {
            for (String token : request.tokens()) {
                owner.add(request);
                tokenOf.add(token);
            }
        }
        return new Fold(requests, owner, tokenOf);
    }

    private record Fold(List<PushRequest> requests, List<PushRequest> owner, List<String> tokenOf) {

        List<PushOutcome> apply(FcmBulkPushSender.TokenResult... results) {
            return FcmBulkPushSender.fold(requests, owner, tokenOf, List.of(results));
        }
    }

    private static FcmBulkPushSender.TokenResult ok() {
        return FcmBulkPushSender.TokenResult.ok();
    }

    private static FcmBulkPushSender.TokenResult error(MessagingErrorCode code) {
        return FcmBulkPushSender.TokenResult.error(code);
    }

    // =====================================================================
    @Nested
    @DisplayName("알림 단위로 되돌리기")
    class PerNotification {

        @Test
        @DisplayName("여러 알림이 한 응답에 섞여 와도 각자의 결과를 받는다")
        void splitsOneResponseAcrossNotifications() {
            PushRequest first = request("tok-a");
            PushRequest second = request("tok-b");
            Fold fold = flatten(List.of(first, second));

            List<PushOutcome> outcomes = fold.apply(ok(), error(MessagingErrorCode.UNREGISTERED));

            assertThat(outcomes.get(0).notificationId()).isEqualTo(first.notificationId());
            assertThat(outcomes.get(0).success()).isTrue();
            assertThat(outcomes.get(1).notificationId()).isEqualTo(second.notificationId());
            assertThat(outcomes.get(1).success()).isFalse();
        }

        @Test
        @DisplayName("토큰 하나라도 성공하면 그 알림은 성공이다 — 사용자에게 도달했다")
        void anyTokenSuccessWins() {
            PushRequest request = request("tok-dead", "tok-live");

            List<PushOutcome> outcomes = flatten(List.of(request))
                    .apply(error(MessagingErrorCode.UNREGISTERED), ok());

            assertThat(outcomes).singleElement()
                    .satisfies(outcome -> assertThat(outcome.success()).isTrue());
        }
    }

    @Nested
    @DisplayName("죽은 토큰 귀속")
    class DeadTokens {

        @Test
        @DisplayName("죽은 토큰을 실제로 낸 알림에만 붙인다 — 남의 기기를 내리면 안 된다")
        void attributesDeadTokenToItsOwner() {
            PushRequest healthy = request("tok-live");
            PushRequest broken = request("tok-dead");
            Fold fold = flatten(List.of(healthy, broken));

            List<PushOutcome> outcomes = fold.apply(ok(), error(MessagingErrorCode.UNREGISTERED));

            assertThat(outcomes.get(0).deadTokens()).isEmpty();
            assertThat(outcomes.get(1).deadTokens()).containsExactly("tok-dead");
        }

        @Test
        @DisplayName("INVALID_ARGUMENT 도 죽은 토큰이다")
        void invalidArgumentIsDead() {
            PushRequest request = request("tok-bad");

            List<PushOutcome> outcomes = flatten(List.of(request))
                    .apply(error(MessagingErrorCode.INVALID_ARGUMENT));

            assertThat(outcomes).singleElement()
                    .satisfies(outcome -> assertThat(outcome.deadTokens()).containsExactly("tok-bad"));
        }
    }

    @Nested
    @DisplayName("재시도 판정")
    class Retryable {

        @Test
        @DisplayName("일시적 오류는 재시도 대상이다 — SQS 메시지를 남긴다")
        void transientErrorsRetry() {
            PushRequest request = request("tok-x");

            List<PushOutcome> outcomes = flatten(List.of(request))
                    .apply(error(MessagingErrorCode.UNAVAILABLE));

            assertThat(outcomes).singleElement()
                    .satisfies(outcome -> assertThat(outcome.retryable()).isTrue());
        }

        @Test
        @DisplayName("죽은 토큰은 재시도 대상이 아니다 — 다시 받아도 결과가 같다")
        void deadTokenIsNotRetryable() {
            PushRequest request = request("tok-y");

            List<PushOutcome> outcomes = flatten(List.of(request))
                    .apply(error(MessagingErrorCode.UNREGISTERED));

            assertThat(outcomes).singleElement()
                    .satisfies(outcome -> assertThat(outcome.retryable()).isFalse());
        }

        @Test
        @DisplayName("보낼 토큰이 없으면 NO_TOKEN 이고 재시도하지 않는다")
        void noTokenIsTerminal() {
            PushRequest request = requestWithoutToken();

            List<PushOutcome> outcomes = flatten(List.of(request)).apply();

            assertThat(outcomes).singleElement().satisfies(outcome -> {
                assertThat(outcome.success()).isFalse();
                assertThat(outcome.retryable()).as("다시 받아도 토큰은 여전히 없다").isFalse();
                assertThat(outcome.errorCode()).isEqualTo("NO_TOKEN");
            });
        }
    }
}
