package com.ruleup.ruleup_backend.verification;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * 여러 챌린지에 동시에 참여 중일 때, 한 챌린지의 신호가 다른 챌린지를 인증하지 않는지 검증한다.
 *
 * <p>배경 — 서로 다른 장소를 인증하는 두 챌린지에 참여 중일 때 한 장소만 다녀와도 두 챌린지가 모두 인증되던
 * 버그가 있었다. sync 는 유저 단위 통로라 한 요청에 <b>모든 챌린지의 신호가 섞여</b> 들어오는데, 평가기가
 * 자기 챌린지 신호만 골라내지 않으면 남의 장소 방문이 내 인증이 된다.
 *
 * <p>차단 계약
 * <ul>
 *   <li>지오펜스 전환의 {@code geofenceId} 는 {@code challengeMemberId} 다 — 평가기는 자기 memberId 것만 센다.</li>
 *   <li>측위 포인트(fallback)는 <b>그 멤버의 앵커 반경</b> 안일 때만 체류로 센다.</li>
 *   <li>앱 사용 이벤트는 <b>그 멤버의 대상 앱</b>일 때만 센다.</li>
 * </ul>
 * 이 세 경계가 다시 뚫리면 여기서 깨진다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class VerificationCrossChallengeIT extends VerificationApiSupport {

    /** 서울 강남 언저리 — 두 앵커는 서로 반경 밖(약 4km)이라 한쪽 방문이 다른 쪽에 닿지 않는다. */
    private static final double GYM_LAT = 37.4979, GYM_LNG = 127.0276;
    private static final double LIBRARY_LAT = 37.5326, LIBRARY_LNG = 127.0246;

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    private MvcResult sync(String token, List<Map<String, Object>> signals) throws Exception {
        MvcResult res = postJsonAuth("/api/v1/verifications/sync", token, syncBody(signals));
        assertThat(res.getResponse().getStatus()).as("sync 응답").isEqualTo(200);
        return res;
    }

    /** dwell 30분짜리 "장소 방문" 챌린지 params. */
    private static String visitParams() {
        return "{\"duration_min\":30,\"radius_m\":100}";
    }

    @Nested
    @DisplayName("장소 인증 — 지오펜스")
    class Geofence {

        @Test
        @DisplayName("장소가 다른 두 챌린지에 참여 중일 때, 한쪽만 다녀오면 그 한쪽만 인증된다")
        void onlyTheVisitedChallengeIsVerified() throws Exception {
            Member me = member(uniq("cross-gf"));

            UUID gymChallenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
            UUID gymMember = insertReadyMember(gymChallenge, me.id(),
                    anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);

            UUID libraryChallenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
            UUID libraryMember = insertReadyMember(libraryChallenge, me.id(),
                    anchor(LIBRARY_LAT, LIBRARY_LNG, 100, "도서관"), null);

            // 헬스장에만 1시간 체류. 도서관 근처에는 가지 않았다.
            sync(me.token(), List.of(
                    geofenceSignal(gymMember, "ENTER", todayAt(9, 0)),
                    geofenceSignal(gymMember, "EXIT", todayAt(10, 0))));

            assertThat(todayStatusOf(gymMember)).as("다녀온 챌린지").isEqualTo("SUCCESS");
            assertThat(todayStatusOf(libraryMember))
                    .as("가지 않은 챌린지가 함께 인증되면 안 된다")
                    .isIn(null, "PENDING");
            assertThat(dwellMinutesOf(libraryMember))
                    .as("가지 않은 챌린지의 체류 시간은 0분이어야 한다")
                    .isIn(null, 0L);
            assertThat(dwellMinutesOf(gymMember))
                    .as("다녀온 챌린지에는 체류 60분이 쌓여야 한다 — 판정이 실제로 돌았다는 증거")
                    .isEqualTo(60L);
        }

        @Test
        @DisplayName("OS 가 확정한 DWELL 전환도 자기 챌린지에서만 인정된다")
        void dwellTransitionDoesNotLeak() throws Exception {
            Member me = member(uniq("cross-dwell"));

            UUID gymChallenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
            UUID gymMember = insertReadyMember(gymChallenge, me.id(),
                    anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);
            UUID libraryChallenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
            UUID libraryMember = insertReadyMember(libraryChallenge, me.id(),
                    anchor(LIBRARY_LAT, LIBRARY_LNG, 100, "도서관"), null);

            sync(me.token(), List.of(geofenceSignal(gymMember, "DWELL", todayAt(9, 30))));

            assertThat(todayStatusOf(gymMember)).isEqualTo("SUCCESS");
            assertThat(todayStatusOf(libraryMember)).isIn(null, "PENDING");
        }

        @Test
        @DisplayName("세 챌린지에 참여 중이어도 신호가 온 하나만 인증된다")
        void threeChallengesOnlyOneVerified() throws Exception {
            Member me = member(uniq("cross-three"));

            UUID c1 = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
            UUID m1 = insertReadyMember(c1, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);
            UUID c2 = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
            UUID m2 = insertReadyMember(c2, me.id(), anchor(LIBRARY_LAT, LIBRARY_LNG, 100, "도서관"), null);
            UUID c3 = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
            UUID m3 = insertReadyMember(c3, me.id(), anchor(37.5665, 126.9780, 100, "스터디카페"), null);

            sync(me.token(), List.of(
                    geofenceSignal(m2, "ENTER", todayAt(13, 0)),
                    geofenceSignal(m2, "EXIT", todayAt(14, 0))));

            assertThat(todayStatusOf(m2)).isEqualTo("SUCCESS");
            assertThat(todayStatusOf(m1)).isIn(null, "PENDING");
            assertThat(todayStatusOf(m3)).isIn(null, "PENDING");
        }

        @Test
        @DisplayName("장소 방문 신호가 '장소 피하기' 챌린지를 위반으로 만들지 않는다")
        void visitDoesNotViolateAnotherChallengesAvoidZone() throws Exception {
            Member me = member(uniq("cross-avoid"));

            UUID gymChallenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
            UUID gymMember = insertReadyMember(gymChallenge, me.id(),
                    anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);

            UUID avoidChallenge = insertAutoChallenge(me.id(), "GPS_AVOID", "GEOFENCE",
                    "{\"duration_min\":0,\"radius_m\":100}");
            UUID avoidMember = insertReadyMember(avoidChallenge, me.id(),
                    anchor(LIBRARY_LAT, LIBRARY_LNG, 100, "편의점"), null);

            // 헬스장(다른 챌린지의 장소)에 들어갔다. 편의점 근처에는 가지 않았다.
            sync(me.token(), List.of(
                    geofenceSignal(gymMember, "ENTER", todayAt(9, 0)),
                    geofenceSignal(gymMember, "EXIT", todayAt(10, 0))));

            assertThat(todayStatusOf(gymMember)).isEqualTo("SUCCESS");
            assertThat(todayStatusOf(avoidMember))
                    .as("남의 장소 진입이 내 회피 챌린지의 위반이 되면 안 된다")
                    .isIn(null, "PENDING");
        }
    }

    @Nested
    @DisplayName("장소 인증 — 측위 포인트 fallback")
    class LocationFallback {

        @Test
        @DisplayName("측위 포인트는 내 앵커 반경 안일 때만 체류로 세고, 다른 챌린지 앵커는 건드리지 않는다")
        void locationPointsCountOnlyInsideOwnAnchor() throws Exception {
            Member me = member(uniq("cross-loc"));

            UUID gymChallenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
            UUID gymMember = insertReadyMember(gymChallenge, me.id(),
                    anchor(GYM_LAT, GYM_LNG, 200, "헬스장"), null);
            UUID libraryChallenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
            UUID libraryMember = insertReadyMember(libraryChallenge, me.id(),
                    anchor(LIBRARY_LAT, LIBRARY_LNG, 200, "도서관"), null);

            // 헬스장 좌표에서 5분 간격으로 40분간 머문 측위 기록(지오펜스 전환은 없음).
            sync(me.token(), List.of(locationSignal(GYM_LAT, GYM_LNG, List.of(
                    todayAt(9, 0), todayAt(9, 5), todayAt(9, 10), todayAt(9, 15),
                    todayAt(9, 20), todayAt(9, 25), todayAt(9, 30), todayAt(9, 35), todayAt(9, 40)))));

            assertThat(todayStatusOf(gymMember)).as("내 앵커 반경 안 체류").isEqualTo("SUCCESS");
            assertThat(todayStatusOf(libraryMember))
                    .as("반경 밖 챌린지가 같은 측위 기록으로 인증되면 안 된다")
                    .isIn(null, "PENDING");
        }
    }

    @Nested
    @DisplayName("앱 사용 시간")
    class ScreenTime {

        @Test
        @DisplayName("대상 앱이 다른 두 챌린지에서, 한 앱을 썼다고 다른 앱 챌린지가 인증되지 않는다")
        void usageCountsOnlyForOwnTargetApp() throws Exception {
            Member me = member(uniq("cross-app"));

            UUID readingChallenge = insertAutoChallenge(me.id(), "SCREEN_TIME_MIN", "USAGE",
                    "{\"duration_min\":30}");
            UUID readingMember = insertReadyMember(readingChallenge, me.id(), null,
                    screenApps("com.ridi.books"));

            UUID studyChallenge = insertAutoChallenge(me.id(), "SCREEN_TIME_MIN", "USAGE",
                    "{\"duration_min\":30}");
            UUID studyMember = insertReadyMember(studyChallenge, me.id(), null,
                    screenApps("com.duolingo"));

            // 독서 앱만 40분 썼다.
            sync(me.token(), List.of(
                    usageSignal("com.ridi.books", todayAt(20, 0), todayAt(20, 40))));

            assertThat(todayStatusOf(readingMember)).as("실제로 쓴 앱").isEqualTo("SUCCESS");
            assertThat(todayStatusOf(studyMember))
                    .as("쓰지 않은 앱의 챌린지가 함께 인증되면 안 된다")
                    .isIn(null, "PENDING");
        }

        @Test
        @DisplayName("다른 챌린지의 앱 사용이 '최대 사용' 챌린지의 위반이 되지 않는다")
        void otherAppUsageDoesNotBreachMaxUsage() throws Exception {
            Member me = member(uniq("cross-appmax"));

            UUID readingChallenge = insertAutoChallenge(me.id(), "SCREEN_TIME_MIN", "USAGE",
                    "{\"duration_min\":30}");
            UUID readingMember = insertReadyMember(readingChallenge, me.id(), null,
                    screenApps("com.ridi.books"));

            UUID snsChallenge = insertAutoChallenge(me.id(), "SCREEN_TIME_MAX", "USAGE",
                    "{\"duration_min\":10}");
            UUID snsMember = insertReadyMember(snsChallenge, me.id(), null,
                    screenApps("com.instagram.android"));

            sync(me.token(), List.of(
                    usageSignal("com.ridi.books", todayAt(20, 0), todayAt(20, 40))));

            assertThat(todayStatusOf(readingMember)).isEqualTo("SUCCESS");
            assertThat(todayStatusOf(snsMember))
                    .as("독서 앱 40분이 SNS 10분 제한을 깨면 안 된다")
                    .isIn(null, "PENDING");
        }
    }
}
