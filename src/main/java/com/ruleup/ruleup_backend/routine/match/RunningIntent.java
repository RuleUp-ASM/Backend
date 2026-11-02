package com.ruleup.ruleup_backend.routine.match;

import com.ruleup.ruleup_backend.routine.domain.RoutineTemplate;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 러닝을 말하는 방은 움직임 루틴으로 자동 인증하지 않는다(#368).
 *
 * <p>움직임 루틴(HEALTH)은 하루 동안의 걸음·이동 거리 <b>합계</b>로 판정한다. 그 신호로는 걷기와 뛰기를,
 * 「나눠서 5km」와 「한 번에 5km」를 가를 수 없다 — 「매일 5km 러닝」 방이 출퇴근 걷기만으로 성공한다.
 * 운동 앱의 「시작」 버튼에 기대는 판정은 자동 인증이 아니어서 택하지 않았고, 걸음 박자·페이스로 가르는
 * 방식은 기록이 쪼개져 오는 단위를 실측한 뒤에 정한다. 그때까지 러닝 방은 수동 인증으로 만든다.
 *
 * <p>헷갈리면 수동이 기본값이다(초안 프롬프트와 같은 원칙). 틀린 자동 인증은 성실한 사용자를 실패로,
 * 혹은 걷기만 한 사용자를 성공으로 판정하지만, 수동은 직접 인증하면 그만이다.
 */
public final class RunningIntent {

    /** 하루 합계로 판정하는 움직임 방식 — 레거시 태그도 v2 에서 HEALTH 로 라우팅된다. */
    private static final Set<String> MOVEMENT_METHODS = Set.of("HEALTH", "GPS_DISTANCE", "ACTIVITY");

    private static final Pattern RUNNING = Pattern.compile(
            "러닝|런닝|달리기|달리자|달리는|달린다|달려|뛰기|뛰자|뛰는|뛴다|뛰어|뛸|조깅|마라톤|러너"
                    + "|(?<![a-z])(run|runs|running|runner|jog|jogging|marathon)(?![a-z])",
            Pattern.CASE_INSENSITIVE);

    private RunningIntent() {}

    /** 이 루틴으로 자동 인증하면 러닝을 증명하지 못하는가 — 움직임 루틴이고 글 어딘가에서 러닝을 말한다. */
    public static boolean blocksAuto(RoutineTemplate template, String... texts) {
        return template != null && MOVEMENT_METHODS.contains(template.getVerificationMethod()) && mentions(texts);
    }

    static boolean mentions(String... texts) {
        if (texts == null) return false;
        for (String t : texts) {
            if (t != null && RUNNING.matcher(t).find()) return true;
        }
        return false;
    }
}
