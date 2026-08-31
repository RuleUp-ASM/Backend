package com.ruleup.ruleup_backend.sanction;

import com.ruleup.ruleup_backend.sanction.domain.*;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.User;
import com.ruleup.ruleup_backend.user.domain.UserStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 제재 집행과 해제 — 백오피스 백엔드 4-2, 온보딩 부록 A.
 *
 * <p>이 클래스가 지켜야 할 계약은 하나다. <b>제재 생성과 {@code users.status} 전이는 한
 * 트랜잭션이어야 한다.</b> 게이트는 status 가 SUSPENDED 일 때만 {@code sanctions} 를 읽고,
 * SUSPENDED 인데 활성 제재가 없으면 스스로 ACTIVE 로 되돌리도록 방어돼 있다. 그래서 두 문장이
 * 나뉘어 하나만 성공하면 <b>제재가 조용히 풀린다.</b>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SanctionService {

    private final SanctionRepository sanctionRepository;
    private final BanEntryRepository banEntryRepository;
    private final SanctionHashes hashes;
    private final UserRepository userRepository;

    // ===== 집행 =====

    /**
     * 제재 집행. 순서가 계약이다 — {@code sanctions} INSERT → {@code users.status} 전이 →
     * BAN 이면 밴리스트 적재까지가 <b>한 트랜잭션</b>이고, 전 챌린지 자동 탈퇴와 필수(A) 고지는
     * 커밋 후 아웃박스로 나간다(백오피스 PR 에서 붙인다).
     */
    @Transactional
    public Sanction impose(UUID userId, SanctionTrack track, SanctionType type, FeatureCode featureCode,
                           SanctionReason reasonCode, String reasonText, SanctionSource source,
                           UUID sourceId, UUID operatorId, Instant endsAt) {
        User user = userRepository.findById(userId).orElseThrow();
        Instant now = Instant.now();

        Sanction sanction = sanctionRepository.save(Sanction.of(
                userId, track, type, featureCode, reasonCode, reasonText, source, sourceId,
                operatorId, now, endsAt));

        user.suspend();     // 같은 트랜잭션 — 이게 빠지면 게이트가 sanctions 를 아예 조회하지 않는다

        if (type.isPermanent()) recordBan(user, sanction, now);

        // 자동 트랙에 잠금·영구 정지가 나타나면 "검토 없이 발동된 계정 제재"다.
        if (track == SanctionTrack.AUTO && type != SanctionType.FEATURE_SUSPENSION) {
            log.error("가드레일 위반 — AUTO 트랙에 {} 제재가 집행됐다. sanctionId={}", type, sanction.getId());
        }
        return sanction;
    }

    /** 재검토 인용 해제. 다른 활성 제재가 없으면 계정을 ACTIVE 로 되돌린다. */
    @Transactional
    public void revoke(UUID sanctionId, Instant at) {
        Sanction sanction = sanctionRepository.findById(sanctionId).orElseThrow();
        sanction.revoke(at);
        syncStatus(sanction.getUserId(), at);
    }

    // ===== 조회 =====

    /** 현재 효력이 있는 제재 중 가장 무거운 것. 게이트가 차단 범위를 정하는 데 쓴다. */
    @Transactional(readOnly = true)
    public Optional<Sanction> activeSanction(UUID userId) {
        return heaviest(sanctionRepository.findActive(userId, Instant.now()));
    }

    /**
     * 영구 정지 중인지 — 로그인 게이트가 쓴다.
     * {@code users.status} 만 보면 잠금과 구분되지 않으므로 제재 종류까지 확인한다.
     */
    @Transactional(readOnly = true)
    public boolean isBanActive(UUID userId) {
        return sanctionRepository.findActive(userId, Instant.now()).stream()
                .anyMatch(s -> s.getType() == SanctionType.BAN);
    }

    @Transactional(readOnly = true)
    public List<Sanction> activeSanctions(UUID userId) {
        return sanctionRepository.findActive(userId, Instant.now());
    }

    /**
     * 여러 제재가 겹치면 <b>차단 범위가 넓은 쪽</b>을 적용한다. BAN > LOCK > FEATURE_SUSPENSION.
     * 좁은 쪽을 고르면 잠금 중인 계정이 기능 정지 수준으로만 막히는 구멍이 생긴다.
     */
    private Optional<Sanction> heaviest(List<Sanction> actives) {
        return actives.stream().max((a, b) -> Integer.compare(weight(a.getType()), weight(b.getType())));
    }

    private int weight(SanctionType type) {
        return switch (type) {
            case BAN -> 3;
            case LOCK -> 2;
            case FEATURE_SUSPENSION -> 1;
        };
    }

    // ===== 탈퇴·복원 =====

    /**
     * 탈퇴 — 진행 중인 제재의 잔여 기간을 얼린다.
     * 얼리지 않으면 탈퇴한 채 시간을 흘려보내 제재를 소진시킬 수 있다.
     */
    @Transactional
    public void freezeAll(UUID userId, Instant now) {
        sanctionRepository.findFreezable(userId, now).forEach(s -> s.freeze(now));
    }

    /** 복원 — 얼려둔 기간만큼 다시 카운트다운을 시작하고, 남은 제재가 있으면 SUSPENDED 로 복귀한다. */
    @Transactional
    public void thawAll(UUID userId, Instant now) {
        sanctionRepository.findByUserIdAndFrozenRemainingSecIsNotNull(userId).forEach(s -> s.thaw(now));
        syncStatus(userId, now);
    }

    // ===== 해제 배치 =====

    /**
     * 기간이 지난 제재를 해제하고 계정을 되돌린다.
     *
     * <p>⚠️ 조건을 {@code ends_at IS NULL OR ends_at <= now} 로 넓히면 <b>동결된 제재와 영구 정지가
     * 통째로 풀린다</b> — 둘 다 {@code ends_at} 이 null 이기 때문이다. 쿼리에서 이미 좁혀 뒀다.
     */
    @Transactional
    public int releaseExpired(Instant now) {
        List<Sanction> expired = sanctionRepository.findExpired(now);
        expired.stream().map(Sanction::getUserId).distinct().forEach(userId -> syncStatus(userId, now));
        return expired.size();
    }

    // ===== 상태 동기화 =====

    /**
     * 활성 제재 유무에 맞춰 {@code users.status} 를 맞춘다.
     *
     * <p>해제 배치가 밀려도 사용자가 해제일 이후까지 묶이지 않게 하는 방어이며, 게이트가 매 요청
     * 호출한다. 뒤집어 말하면 <b>제재 생성이 두 문장으로 나뉘어 실패하면 여기서 조용히 풀린다</b> —
     * 그래서 {@link #impose} 가 한 트랜잭션이어야 한다.
     */
    @Transactional
    public UserStatus syncStatus(UUID userId, Instant now) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null || user.isWithdrawn()) return (user == null) ? null : user.getStatus();

        boolean hasActive = !sanctionRepository.findActive(userId, now).isEmpty();
        if (hasActive && user.getStatus() != UserStatus.SUSPENDED) user.suspend();
        if (!hasActive && user.getStatus() == UserStatus.SUSPENDED) user.activate();
        return user.getStatus();
    }

    // ===== 밴리스트 =====

    private void recordBan(User user, Sanction sanction, Instant at) {
        String oauthHash = hashes.ofOauth(user.getOauthProvider().name(), user.getOauthSubject());
        if (banEntryRepository.existsByOauthHash(oauthHash)) return;      // 멱등
        banEntryRepository.save(BanEntry.of(oauthHash,
                hashes.ofInstallation(user.getInstallationId()), sanction.getId(), at));
    }

    /** 가입·로그인 게이트 — 영구 정지 계정이 계정을 바꿔 돌아오는 경로를 막는다. */
    @Transactional(readOnly = true)
    public boolean isBanned(String provider, String subject, String installationId) {
        if (banEntryRepository.existsByOauthHash(hashes.ofOauth(provider, subject))) return true;
        String installHash = hashes.ofInstallation(installationId);
        return installHash != null && banEntryRepository.existsByInstallationHash(installHash);
    }
}
