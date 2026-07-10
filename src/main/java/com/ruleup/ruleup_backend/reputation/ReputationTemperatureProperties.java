package com.ruleup.ruleup_backend.reputation;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 온도 계산 튜닝 상수(테크스펙 §5 "설정값으로 외부화 — 밴드 재조정 시 코드 변경 없이 대응").
 * 모든 값에 스펙 기본값을 넣어 두어 yaml(app.reputation) 미설정이어도 동작한다.
 */
@Component
@ConfigurationProperties(prefix = "app.reputation")
@Getter
@Setter
public class ReputationTemperatureProperties {

    /** 페이스 점수 피벗 r (이 성공률에서 c=0). */
    private double pacePivot = 0.75;
    /** 페이스 점수 스케일(피벗에서 ±range 벗어나면 ±1로 clamp). */
    private double paceRange = 0.25;

    /** 볼륨 EMA 학습률 η_V (=1/150, 시간상수 150일). */
    private double volumeLearningRate = 1.0 / 150.0;

    /** 연차 적립 자격 문턱 s_d ≥ threshold. */
    private double qualifyingThreshold = 0.6;
    /** 자격 누적 며칠 후부터 B 적립 시작(1년=365). */
    private int tenureAccrualAfterDays = 365;
    /** 자격일당 B 적립량(+1/365). */
    private double tenureAccrualPerDay = 1.0 / 365.0;
    /** 비자격일당 B 감소량(−2/365, 6개월 방치 = 연차 1년 상실). */
    private double tenureDecayPerDay = 2.0 / 365.0;
    /** B 상한. */
    private double tenureCap = 6.0;
    /** 루틴 전환 공백 유예일(이내면 자격 연속으로 간주, 감소 없음). */
    private int graceDays = 7;

    /** 온도 하한·상한(0·95·100 도달 불가). */
    private double temperatureFloor = 5.0;
    private double temperatureCeiling = 93.0;
    /** 신규/장기 무활동 기준 온도. */
    private double initialTemperature = 36.5;

    /** 배치 누락 시 최대 소급 스텝(폭주 방지). */
    private int maxCatchupDays = 400;
}
