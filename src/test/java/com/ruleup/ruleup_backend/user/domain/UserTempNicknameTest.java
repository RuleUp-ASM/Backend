package com.ruleup.ruleup_backend.user.domain;

import com.ruleup.ruleup_backend.common.UuidGenerator;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * deriveTempNickname()은 PK(UUID v7)에서 결정적으로 파생하는 임시 승인 닉네임(가입 직후 approved_nickname 초기값).
 * 핵심: v7의 앞 구간은 타임스탬프라 같은 시간대 가입자끼리 겹치므로 <b>뒤 8자리</b>(랜덤 구간)를 써야 한다.
 * (approved_nickname VARCHAR(12) 제약상 prefix 없이 8자만 사용 — DB 정리 문서 §6)
 */
class UserTempNicknameTest {

    /** id만 지정된 User를 만든다(create()는 id를 랜덤 생성하므로 파생 로직 검증용으로 직접 주입). */
    private static User userWithId(UUID id) {
        try {
            User u = new User();
            Field f = User.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(u, id);
            return u;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void format_isLast8Hex() {
        UUID id = UUID.fromString("0190abcd-1234-7abc-8def-0000001a2b3c");
        assertThat(userWithId(id).deriveTempNickname()).isEqualTo("001a2b3c");   // 하이픈 제거 후 뒤 8자리
    }

    @Test
    void deterministic_sameIdSameValue() {
        UUID id = UuidGenerator.generate();
        User u = userWithId(id);
        assertThat(u.deriveTempNickname()).isEqualTo(u.deriveTempNickname());              // 항상 동일
        assertThat(userWithId(id).deriveTempNickname()).isEqualTo(u.deriveTempNickname()); // 같은 id → 같은 값
    }

    /**
     * 타임스탬프 구간이 동일한(가입 시각이 가까운) 서로 다른 v7 UUID 두 개는
     * 서로 다른 임시 닉네임을 가져야 한다. (구버그: 앞자리를 쓰면 둘이 충돌했다.)
     */
    @Test
    void closeTimestamps_produceDifferentTempNicknames() {
        // 앞부분(타임스탬프·variant)은 동일, 랜덤 꼬리만 다른 두 UUID.
        UUID a = UUID.fromString("0190abcd-1234-7abc-8def-000000aaaaaa");
        UUID b = UUID.fromString("0190abcd-1234-7abc-8def-000000bbbbbb");

        String hexA = a.toString().replace("-", "");
        String hexB = b.toString().replace("-", "");
        assertThat(hexA.substring(0, 8)).isEqualTo(hexB.substring(0, 8));      // 앞 8자리는 같음(구버그가 충돌하던 지점)

        assertThat(userWithId(a).deriveTempNickname())
                .isNotEqualTo(userWithId(b).deriveTempNickname());             // 새 로직(뒤 8자리)은 구분됨
    }

    /** 실제 v7 생성기로도 연속 생성 시(같은 시간대) 서로 다른 임시 닉네임이 나오는지 확인. */
    @Test
    void consecutiveV7Ids_areDistinct() {
        User u1 = userWithId(UuidGenerator.generate());
        User u2 = userWithId(UuidGenerator.generate());
        assertThat(u1.deriveTempNickname()).isNotEqualTo(u2.deriveTempNickname());
    }

    // ===== 사전 검사 단계의 충돌 재시도 (DB 정리 문서 §6) =====
    // INSERT 시점 충돌(경합)까지 포함한 검증은 TempNicknameCollisionIT 가 실제 DB로 수행한다.

    private static final TempNicknameGenerator REAL_GENERATOR = new RandomTempNicknameGenerator();

    /** 생성기가 준 첫 후보가 비어 있으면 그대로 쓴다(불필요한 재발급 금지). */
    @Test
    void allocator_keepsFirstCandidate_whenAvailable() {
        User u = User.create(OAuthProvider.KAKAO, "sub", null, "성은이", null, null);
        List<String> issued = new ArrayList<>();
        TempNicknameAllocator allocator = new TempNicknameAllocator(() -> {
            String candidate = REAL_GENERATOR.next();
            issued.add(candidate);
            return candidate;
        });

        allocator.assign(u, taken -> false);   // 아무것도 점유되지 않은 상태

        assertThat(issued).hasSize(1);
        assertThat(u.getApprovedNickname()).isEqualTo(issued.getFirst());
    }

    /** 이미 점유된 값이면 다른 8자리로 재시도해서 사용 가능한 값을 얻는다. */
    @Test
    void allocator_retriesUntilFreeCandidate() {
        User u = User.create(OAuthProvider.KAKAO, "sub", null, "성은이", null, null);
        Set<String> occupied = new HashSet<>(Set.of("aaaaaaaa"));   // 첫 후보는 이미 다른 사람이 쓰는 중

        List<String> issued = new ArrayList<>();
        TempNicknameAllocator allocator = new TempNicknameAllocator(() -> {
            String candidate = issued.isEmpty() ? "aaaaaaaa" : REAL_GENERATOR.next();
            issued.add(candidate);
            return candidate;
        });

        allocator.assign(u, occupied::contains);

        assertThat(u.getApprovedNickname())
                .isNotEqualTo("aaaaaaaa")                  // 충돌한 값은 버린다
                .hasSize(8)
                .containsPattern("^[0-9a-f]{8}$");
        assertThat(issued).hasSizeGreaterThanOrEqualTo(2); // 최소 한 번은 재발급했다
    }

    /** 연속 충돌이 상한을 넘으면 조용히 중복 저장하지 않고 실패시킨다(UNIQUE 위반 방지). */
    @Test
    void allocator_failsFast_whenEveryCandidateTaken() {
        User u = User.create(OAuthProvider.KAKAO, "sub", null, "성은이", null, null);
        TempNicknameAllocator allocator = new TempNicknameAllocator(REAL_GENERATOR);

        assertThatThrownBy(() -> allocator.assign(u, candidate -> true))
                .isInstanceOf(IllegalStateException.class);
    }

    /** 생성기 후보는 8자리 hex 이고 매번 달라야 한다(임시 닉네임끼리의 충돌 확률을 낮춘다). */
    @Test
    void generator_producesDistinct8HexCandidates() {
        Set<String> candidates = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            String candidate = REAL_GENERATOR.next();
            assertThat(candidate).hasSize(8).containsPattern("^[0-9a-f]{8}$");
            candidates.add(candidate);
        }
        assertThat(candidates).hasSize(50);
    }

    /** 가입 직후 approved_nickname 은 임시 닉네임으로 초기화되고, 승인 시 신청값으로 교체된다. */
    @Test
    void approvedNickname_initializedWithTempAndReplacedOnApproval() {
        User u = User.create(OAuthProvider.KAKAO, "sub", null, "성은이", null, null);
        assertThat(u.getApprovedNickname()).isEqualTo(u.deriveTempNickname());
        assertThat(u.visibleNicknameTo(null)).isEqualTo(u.deriveTempNickname());   // 타인 화면
        assertThat(u.visibleNicknameTo(u.getId())).isEqualTo("성은이");             // 본인 화면

        u.approveNickname();
        assertThat(u.getApprovedNickname()).isEqualTo("성은이");
        assertThat(u.visibleNicknameTo(null)).isEqualTo("성은이");
    }
}
