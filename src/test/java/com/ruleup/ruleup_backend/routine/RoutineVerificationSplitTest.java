package com.ruleup.ruleup_backend.routine;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.routine.domain.RoutineTemplate;
import com.ruleup.ruleup_backend.routine.domain.SignalSource;
import com.ruleup.ruleup_backend.routine.domain.VerificationType;
import com.ruleup.ruleup_backend.routine.service.RoutineCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 인증 테이블 분리(RoutineTemplate ↔ RoutineVerification, 1:1) 검증.
 * 카탈로그가 fetch join 으로 인증 정의를 함께 로드하고, 위임 게터가 올바른 값을 돌려주는지 본다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RoutineVerificationSplitTest {

    @Autowired
    RoutineCatalog catalog;

    @Test
    void 시드_105개_모두_로드되고_위임게터가_동작한다() {
        assertThat(catalog.candidates()).hasSize(105);

        // id 1: '헬스장 가서 1시간 운동' — 자동(지오펜스), 수동 PHOTO, method GPS_PRESENCE
        RoutineTemplate gym = catalog.findById(1L).orElseThrow();
        assertThat(gym.supportsAuto()).isTrue();
        assertThat(gym.getAutoVerificationType()).isEqualTo(VerificationType.PHONE);
        assertThat(gym.getAutoSignalSource()).isEqualTo(SignalSource.GEOFENCE);
        assertThat(gym.getManualSignalSource()).isEqualTo(SignalSource.PHOTO);
        assertThat(gym.getAutoRequiredPermissions()).contains("ACCESS_FINE_LOCATION");
        assertThat(gym.getVerificationMethod()).isEqualTo("GPS_PRESENCE");

        // id 4: '홈트 30분' — 자동 불가(전부 NULL), 수동 PHOTO, method PHOTO
        RoutineTemplate home = catalog.findById(4L).orElseThrow();
        assertThat(home.supportsAuto()).isFalse();
        assertThat(home.getAutoVerificationType()).isNull();
        assertThat(home.getManualSignalSource()).isEqualTo(SignalSource.PHOTO);
        assertThat(home.getVerificationMethod()).isEqualTo("PHOTO");

        // id 99: '1일 1커밋' — EXTERNAL/GitHub
        RoutineTemplate commit = catalog.findById(99L).orElseThrow();
        assertThat(commit.getAutoVerificationType()).isEqualTo(VerificationType.EXTERNAL);
        assertThat(commit.getAutoExternalService()).isEqualTo("GitHub");
    }
}
