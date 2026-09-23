package com.ruleup.ruleup_backend.me.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name="MeTierHistoryResponse",description="점수 변동 시점별 그래프. 하락 사유는 그래프 응답에 포함하지 않는다.")
public record MeTierHistoryResponse(Best best,List<Point> points,String retentionNote) {
    public record Best(String tier,long score,String date) {}
    public record Point(String occurredAt,String tier,long score) {}
}
