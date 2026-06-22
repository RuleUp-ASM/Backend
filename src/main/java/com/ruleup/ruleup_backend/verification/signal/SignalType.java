package com.ruleup.ruleup_backend.verification.signal;

/** sync 신호 타입(§3.1). 그 외 타입은 무시(ignoredSignalTypes로 회신). */
public enum SignalType { GEOFENCE, LOCATION, RUNNING_SESSION, SCREEN_TIME, SLEEP }
