package com.ruleup.ruleup_backend.user;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/**
 * 마지막 활동일 기록 — 휴면 판정의 근거({@code user_activity.last_active_on}).
 *
 * <p><b>값이 날짜</b>라 같은 날 두 번째부터의 기록은 결과가 같다. 그런데 인증된 요청마다 부르므로
 * 그대로 두면 GET 하나가 곧 DB 쓰기 하나였다(부하테스트 2026-10: 읽기 요청마다 upsert 1건 +
 * 커넥션 대여 1회). 그래서 태스크마다 「오늘 이미 썼다」를 기억하고 같은 날은 건너뛴다.
 * 태스크가 여럿이면 태스크마다 하루 한 번씩 쓴다 — 결과는 같다.
 *
 * <p>로그인·가입처럼 계정 상태가 막 바뀐 직후는 {@link #touchNow} 로 반드시 쓴다 — 같은 날 앞선 기록이
 * 탈퇴 상태라 무시됐을 수 있다(INSERT 가 {@code status<>'WITHDRAWN'} 일 때만 들어간다).
 */
@Service
@RequiredArgsConstructor
public class UserActivityService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final JdbcTemplate jdbc;
    private final Cache<UUID, LocalDate> recordedOn = Caffeine.newBuilder()
            .maximumSize(200_000).expireAfterWrite(Duration.ofHours(25)).build();

    /** 요청마다 부른다. 오늘 이미 기록했으면 DB 를 건드리지 않는다(트랜잭션·커넥션도 열지 않는다). */
    public void touch(UUID userId) {
        LocalDate today = LocalDate.now(KST);
        if (today.equals(recordedOn.getIfPresent(userId))) return;
        write(userId, today);
    }

    /** 로그인·가입·복원 직후 — 기억과 상관없이 기록한다. 호출한 쪽 트랜잭션이 있으면 거기에 합류한다. */
    public void touchNow(UUID userId) {
        write(userId, LocalDate.now(KST));
    }

    private void write(UUID userId, LocalDate today) {
        byte[] id = ByteBuffer.allocate(16).putLong(userId.getMostSignificantBits()).putLong(userId.getLeastSignificantBits()).array();
        jdbc.update("INSERT INTO user_activity(user_id,last_active_on) SELECT id,? FROM users " +
                "WHERE id=? AND status<>'WITHDRAWN' ON DUPLICATE KEY UPDATE " +
                "notified_stage=IF(last_active_on<VALUES(last_active_on),'NONE',notified_stage), " +
                "last_active_on=GREATEST(last_active_on,VALUES(last_active_on))", today, id);
        recordedOn.put(userId, today);
    }
}
