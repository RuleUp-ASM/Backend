package com.ruleup.ruleup_backend.notification.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RoutineLabelsTest {

    @Test
    @DisplayName("셋까지는 이름을 다 싣고, 넘으면 「외 N개」로 접는다")
    void summary() {
        assertThat(RoutineLabels.summary(List.of("물 마시기"))).isEqualTo("물 마시기");
        assertThat(RoutineLabels.summary(List.of("a", "b", "c"))).isEqualTo("a, b, c");
        assertThat(RoutineLabels.summary(List.of("a", "b", "c", "d", "e"))).isEqualTo("a, b, c 외 2개");
        assertThat(RoutineLabels.summary(List.of())).isNull();
    }

    @Test
    @DisplayName("제목이 없으면 「챌린지」, 길면 잘라 말줄임표를 붙인다")
    void label() {
        assertThat(RoutineLabels.of(null)).isEqualTo("챌린지");
    }
}
