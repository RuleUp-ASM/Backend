package com.ruleup.ruleup_backend.notification;

import com.ruleup.ruleup_backend.notification.domain.NotificationParams;
import com.ruleup.ruleup_backend.notification.domain.NotificationRegistryValidator;
import com.ruleup.ruleup_backend.notification.domain.NotificationTemplate;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 문구 레지스트리 계약 — <b>제목·본문이 타입과 {@code params} 만으로 정해진다</b>(백엔드 4-1 ①).
 *
 * <p>스프링을 띄우지 않는다. 렌더링이 순수 함수라는 것이 계약이므로, 컨테이너가 필요하면
 * 그 자체가 계약 위반이다.
 */
class NotificationTemplateTest {

    @Nested
    @DisplayName("타입 덮개")
    class Coverage {

        @Test
        @DisplayName("문구 없는 타입은 운영자가 직접 쓰는 공지·마케팅 둘뿐이다")
        void everyTypeHasTextExceptAuthored() {
            assertThat(Arrays.stream(NotificationType.values())
                    .filter(t -> NotificationTemplate.of(t).isEmpty()))
                    .containsExactlyInAnyOrderElementsOf(NotificationTemplate.AUTHORED_TYPES);
        }

        @Test
        @DisplayName("기동 검증이 전 타입·전 변형을 더미 렌더해 통과한다")
        void startupValidationPasses() {
            assertThatCode(() -> new NotificationRegistryValidator().validate())
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("변형")
    class Variants {

        @Test
        @DisplayName("제재는 수단·기한으로 갈린다 — 영구 정지에 해제 예정일을 말하지 않는다")
        void sanctionVariants() {
            assertThat(render(NotificationType.ACCOUNT_SANCTION, "BAN").title())
                    .isEqualTo("계정이 영구 정지됐어요");
            assertThat(render(NotificationType.ACCOUNT_SANCTION, "BAN").body())
                    .doesNotContain("해제 예정일");
            assertThat(render(NotificationType.ACCOUNT_SANCTION, "LOCK_UNTIL").body())
                    .startsWith("해제 예정일까지");
            assertThat(render(NotificationType.ACCOUNT_SANCTION, "REVOKED").title())
                    .isEqualTo("제재가 해제됐어요");
        }

        @Test
        @DisplayName("이미 의미가 있는 파라미터로 갈리는 타입은 variant 를 따로 받지 않는다")
        void reusesExistingParams() {
            NotificationTemplate.Rendered ended = NotificationTemplate.render(
                    NotificationType.CHALLENGE_LIFECYCLE,
                    Map.of(NotificationParams.PHASE, "ENDED"));
            assertThat(ended.title()).isEqualTo("챌린지가 끝났어요");

            NotificationTemplate.Rendered down = NotificationTemplate.render(
                    NotificationType.TIER_CHANGED, Map.of(NotificationParams.DIRECTION, "DOWN"));
            assertThat(down.title()).isEqualTo("티어가 내려갔어요");
        }

        @Test
        @DisplayName("맞는 변형이 없으면 null 이다 — 다른 사건의 문구를 대신 내보내지 않는다")
        void unknownVariantRendersNothing() {
            NotificationTemplate.Rendered rendered = NotificationTemplate.render(
                    NotificationType.ACCOUNT_SANCTION,
                    Map.of(NotificationParams.VARIANT, "WARNING"));

            assertThat(rendered.title()).isNull();
            assertThat(rendered.body()).isNull();
        }

        @Test
        @DisplayName("이의 결과는 변형이 없다 — 자동 인용 구제권이라 기각 상태가 아예 없다")
        void appealHasSingleText() {
            assertThat(NotificationTemplate.of(NotificationType.APPEAL_RESULT))
                    .singleElement()
                    .extracting(NotificationTemplate::variantParam).isNull();
        }
    }

    @Nested
    @DisplayName("치환")
    class Substitution {

        @Test
        @DisplayName("감시자 통지는 누가·어느 방의·어느 약속인지 셋을 채운다")
        void penaltyNotice() {
            NotificationTemplate.Rendered rendered = NotificationTemplate.render(
                    NotificationType.PENALTY_FAILURE_SHARED,
                    Map.of(NotificationParams.ACTOR_NAME, "루피",
                            NotificationParams.CHALLENGE_TITLE, "아침 러닝",
                            NotificationParams.ROUTINE_NAME, "5km 달리기"));

            assertThat(rendered.title()).isEqualTo("감시 알림");
            assertThat(rendered.body())
                    .isEqualTo("루피님이 [아침 러닝]의 5km 달리기 약속을 지키지 못했어요.");
        }

        @Test
        @DisplayName("값이 비면 예외가 아니라 null 이다 — 적재 단계가 폴백 문구로 메운다")
        void missingParamFallsBack() {
            NotificationTemplate.Rendered rendered = NotificationTemplate.render(
                    NotificationType.PENALTY_FAILURE_SHARED,
                    Map.of(NotificationParams.ACTOR_NAME, "루피"));

            assertThat(rendered.title()).as("치환자가 없는 제목은 그대로 나온다").isEqualTo("감시 알림");
            assertThat(rendered.body()).isNull();
        }

        @Test
        @DisplayName("강퇴 본문은 방장이 쓴 사유가 통째로 들어간다")
        void kickReason() {
            assertThat(NotificationTemplate.render(NotificationType.CHALLENGE_KICKED,
                    Map.of(NotificationParams.REASON, "인증을 세 번 놓쳤어요")).body())
                    .isEqualTo("인증을 세 번 놓쳤어요");
        }
    }

    private NotificationTemplate.Rendered render(NotificationType type, String variant) {
        return NotificationTemplate.render(type, Map.of(NotificationParams.VARIANT, variant));
    }
}
