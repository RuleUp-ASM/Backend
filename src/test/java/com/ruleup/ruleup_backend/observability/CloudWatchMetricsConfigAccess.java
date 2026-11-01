package com.ruleup.ruleup_backend.observability;

import java.util.Set;

/** 다른 패키지의 게이지 시험이 CloudWatch 허용 목록(패키지 전용)을 읽게 한다. */
public final class CloudWatchMetricsConfigAccess {

    private CloudWatchMetricsConfigAccess() {}

    public static Set<String> exported() {
        return CloudWatchMetricsConfig.EXPORTED;
    }
}
