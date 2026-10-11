package com.ruleup.ruleup_backend.observability.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class TaskMemorySamplerTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    @DisplayName("페이지 캐시를 뺀 사용량을 태스크 하드 한도·컨테이너 하드 한도와 대조해 더 나쁜 쪽을 쓴다")
    void worstOfTaskAndContainerHardLimit() throws Exception {
        var task = om.readTree("""
                {"Limits":{"CPU":1,"Memory":2048},
                 "Containers":[{"Name":"api","DockerId":"a","Limits":{"Memory":1536}},
                               {"Name":"otel-collector","DockerId":"b","Limits":{}}]}""");
        var stats = om.readTree("""
                {"a":{"memory_stats":{"usage":1677721600,"stats":{"inactive_file":104857600}}},
                 "b":{"memory_stats":{"usage":209715200,"stats":{"cache":0}}}}""");
        // api: (1600-100)MiB / 1536MiB = 97.66% · 태스크: (1500+200)/2048 = 83%
        assertThat(TaskMemorySampler.hardPct(task, stats)).isCloseTo(97.66, within(0.01));
    }

    @Test
    @DisplayName("한도도 통계도 못 읽으면 0 이 아니라 값을 내지 않는다")
    void unknownIsNotZero() throws Exception {
        assertThat(TaskMemorySampler.hardPct(om.readTree("{}"), om.readTree("{}"))).isNull();
    }
}
