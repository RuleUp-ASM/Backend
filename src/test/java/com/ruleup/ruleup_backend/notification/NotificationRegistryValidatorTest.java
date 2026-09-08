package com.ruleup.ruleup_backend.notification;

import com.ruleup.ruleup_backend.notification.domain.NotificationRegistryValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 레지스트리 기동 검증 — 백엔드 4-1 의 「기동 시 전 타입 더미 렌더 검증, 실패 시 기동 중단」.
 *
 * <p>적재가 도메인 트랜잭션 안으로 들어온 대가다. 딥링크 템플릿의 {@code {challege_id}} 오타 하나가
 * 런타임에는 <b>조용히 null 딥링크</b>가 되고, 그건 배포 후에야 드러난다. 그래서 기동 때 전부 한 번
 * 렌더해 보고 어긋나면 <b>뜨지 않는다</b> — 알림이 반쯤 망가진 채 도는 것보다 낫다.
 */
class NotificationRegistryValidatorTest {

    @Test
    @DisplayName("현재 레지스트리 22종은 전부 검증을 통과한다")
    void currentRegistryIsValid() {
        assertThatCode(() -> new NotificationRegistryValidator().validate())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("22종 전부를 실제로 렌더해 본다 — 개수를 세어 누락을 막는다")
    void everyTypeIsRendered() {
        assertThat(new NotificationRegistryValidator().validate()).isEqualTo(22);
    }

    @Test
    @DisplayName("딥링크가 모르는 파라미터를 참조하면 기동을 막는다 — 오타 하나가 딥링크를 통째로 null 로 만든다")
    void unknownDeeplinkParamStopsStartup() {
        assertThatThrownBy(() -> NotificationRegistryValidator.checkTemplate(
                "VERIFICATION_RESULT", "ruleup://challenges/{challege_id}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("challege_id");
    }

    @Test
    @DisplayName("닫히지 않은 치환자도 기동을 막는다")
    void unbalancedPlaceholderStopsStartup() {
        assertThatThrownBy(() -> NotificationRegistryValidator.checkTemplate(
                "VERIFICATION_RESULT", "ruleup://challenges/{challenge_id"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("커스텀 스킴이 아니면 기동을 막는다 — https 앱링크는 외부 웹 노출 이슈가 있다")
    void nonCustomSchemeStopsStartup() {
        assertThatThrownBy(() -> NotificationRegistryValidator.checkTemplate(
                "VERIFICATION_RESULT", "https://ruleup.app/challenges/{challenge_id}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ruleup://");
    }

    @Test
    @DisplayName("알고 있는 파라미터만 쓰면 통과한다")
    void knownParamPasses() {
        assertThatCode(() -> NotificationRegistryValidator.checkTemplate(
                "VERIFICATION_RESULT", "ruleup://challenges/{challenge_id}"))
                .doesNotThrowAnyException();
    }
}
