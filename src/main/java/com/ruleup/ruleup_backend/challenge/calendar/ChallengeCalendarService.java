package com.ruleup.ruleup_backend.challenge.calendar;

import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.recommendation.domain.RoutineOutcome;
import com.ruleup.ruleup_backend.recommendation.repository.RoutineOutcomeRepository;
import com.ruleup.ruleup_backend.verification.domain.Appeal;
import com.ruleup.ruleup_backend.verification.domain.VerificationDaily;
import com.ruleup.ruleup_backend.verification.repository.AppealRepository;
import com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 챌린지 단위 월 캘린더 — 솔로 챌린지 상세의 월 캘린더가 쓴다.
 *
 * <p>원천이 둘인 것은 {@code /me/calendar/{date}} 와 같은 이유다. {@link VerificationDaily} 는
 * 인증 건 id 와 이의 기한을 들고 있어 이의 버튼을 그릴 수 있는 유일한 원천이고,
 * {@link RoutineOutcome} 은 방이 하드 삭제된 뒤에도 남는 내구성 스냅샷이다. <b>인증 건을 먼저
 * 깔고 그 날짜에 인증 건이 없을 때만 스냅샷으로 메운다</b> — 삭제된 방의 과거 기록은 상태만
 * 보이고 이의는 걸 수 없다(대상 인증이 사라졌으므로 정상이다).
 *
 * <p>열람 자격은 <b>참여한 적이 있는가</b>로 본다. 지금 참여 중일 필요는 없다 — 마이페이지가
 * 완료·이탈한 방의 내 기록 열람을 보장하기 때문이다. 반대로 참여한 적이 없으면 403 이다:
 * 남의 판정 이력을 날짜별로 훑을 수 있는 경로를 만들지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChallengeCalendarService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final VerificationDailyRepository dailyRepo;
    private final RoutineOutcomeRepository outcomeRepo;
    private final AppealRepository appealRepo;
    private final JdbcTemplate jdbc;

    public ChallengeCalendarResponse month(UUID userId, UUID challengeId, String month) {
        YearMonth ym = parseMonth(month);
        requireExists(challengeId);
        requireParticipated(userId, challengeId);

        LocalDate from = ym.atDay(1);
        LocalDate to = ym.atEndOfMonth();
        Instant now = Instant.now();

        List<VerificationDaily> dailies = dailyRepo
                .findByUserIdAndChallengeIdAndTargetDateBetween(userId, challengeId, from, to).stream()
                .filter(vd -> vd.getStatus() != VerificationStatus.NOT_TARGET
                        && vd.getStatus() != VerificationStatus.NOT_REQUIRED)
                .toList();
        Set<LocalDate> covered = dailies.stream()
                .map(VerificationDaily::getTargetDate).collect(Collectors.toSet());
        Set<UUID> appealed = appealRepo.findByUserIdOrderByAcceptedAtDesc(userId).stream()
                .map(Appeal::getVerificationDailyId).collect(Collectors.toSet());

        // 날짜순으로 세우는 것은 화면 요구가 아니라 계약이다 — 달력 칸을 채우는 쪽이 정렬을
        // 다시 하지 않아도 되게 서버가 순서를 보장한다.
        TreeMap<LocalDate, ChallengeCalendarResponse.Day> byDate = new TreeMap<>();
        for (VerificationDaily vd : dailies) {
            String status = displayStatus(vd.getStatus(), vd.getTargetDate());
            byDate.put(vd.getTargetDate(), new ChallengeCalendarResponse.Day(
                    vd.getTargetDate().toString(), status, vd.getId().toString(),
                    appealable(status, vd, appealed.contains(vd.getId()), now)));
        }
        for (RoutineOutcome o : outcomeRepo
                .findByUserIdAndChallengeIdAndTargetDateBetween(userId, challengeId, from, to)) {
            if (covered.contains(o.getTargetDate())) continue;
            byDate.put(o.getTargetDate(), new ChallengeCalendarResponse.Day(
                    o.getTargetDate().toString(), displayStatus(o.getStatus(), o.getTargetDate()),
                    null, false));
        }

        return new ChallengeCalendarResponse(
                challengeId.toString(), ym.toString(), new ArrayList<>(byDate.values()));
    }

    /**
     * 저장 상태 + 귀속일 → 화면 상태. {@code /me/calendar/{date}} 와 같은 어휘를 쓴다 —
     * 같은 하루가 두 화면에서 다른 이름으로 보이면 안 된다.
     *
     * <p>귀속일이 끝났는데 아직 확정되지 않았다는 사실만으로 실패 예정이 성립한다. 유예 하루가
     * 정확히 그 구간이다.
     */
    private String displayStatus(VerificationStatus stored, LocalDate targetDate) {
        return switch (stored) {
            case SUCCESS -> "DONE";
            case FAILED -> "FAILED";
            case PENDING -> targetDate.isBefore(LocalDate.now(KST)) ? "FAIL_EXPECTED" : "IN_PROGRESS";
            // 위에서 걸러지므로 도달하지 않는다. 판정 대상이 아닌 날은 배열에 넣지 않는다.
            case NOT_TARGET, NOT_REQUIRED -> "IN_PROGRESS";
        };
    }

    /**
     * 이의 진입 가능 여부 — 마이페이지 §2-10 이 캘린더에서의 이의 진입을 요구한다.
     *
     * <p>대상은 실패했거나 실패 예정인 건뿐이다. 아직 채울 기회가 남은 건과 이미 완료된 건은
     * 이의 대상이 아니다.
     */
    private boolean appealable(String status, VerificationDaily vd, boolean alreadyAppealed, Instant now) {
        if (!"FAILED".equals(status) && !"FAIL_EXPECTED".equals(status)) return false;
        if (alreadyAppealed) return false;
        return vd.getAppealClosesAt() != null && now.isBefore(vd.getAppealClosesAt());
    }

    /** 하드 삭제된 완료 방도 이력이 있으면 존재한다 — 완료 기록 열람이 보장돼야 한다. */
    private void requireExists(UUID challengeId) {
        if (count("SELECT COUNT(*) FROM challenges WHERE id = ? AND deleted_at IS NULL", challengeId) > 0) return;
        if (count("SELECT COUNT(*) FROM challenge_history WHERE challenge_id = ?", challengeId) > 0) return;
        throw new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND);
    }

    /** 지금 참여 중일 필요는 없고 <b>참여한 적</b>이 있으면 된다(이탈·완료 포함). */
    private void requireParticipated(UUID userId, UUID challengeId) {
        if (count("SELECT COUNT(*) FROM challenge_members WHERE challenge_id = ? AND user_id = ?",
                challengeId, userId) > 0) return;
        if (count("SELECT COUNT(*) FROM challenge_member_history WHERE challenge_id = ? AND user_id = ?",
                challengeId, userId) > 0) return;
        throw new BusinessException(ErrorCode.NOT_CHALLENGE_MEMBER);
    }

    private long count(String sql, UUID... ids) {
        Object[] args = new Object[ids.length];
        for (int i = 0; i < ids.length; i++) args[i] = toBytes(ids[i]);
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) throw new BusinessException(ErrorCode.INVALID_CALENDAR_MONTH);
        try {
            return YearMonth.parse(month);
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.INVALID_CALENDAR_MONTH);
        }
    }

    private static byte[] toBytes(UUID u) {
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(u.getMostSignificantBits());
        bb.putLong(u.getLeastSignificantBits());
        return bb.array();
    }
}
