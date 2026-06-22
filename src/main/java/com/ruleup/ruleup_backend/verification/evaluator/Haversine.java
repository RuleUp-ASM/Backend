package com.ruleup.ruleup_backend.verification.evaluator;

/** 두 좌표 간 거리(m). 측위 평가 공용. */
public final class Haversine {
    private Haversine() {}
    private static final double R = 6_371_000.0;   // 지구 반경(m)

    public static double meters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
