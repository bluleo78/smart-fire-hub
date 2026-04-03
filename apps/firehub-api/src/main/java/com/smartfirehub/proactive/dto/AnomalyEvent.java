package com.smartfirehub.proactive.dto;

import java.util.List;

public record AnomalyEvent(
    Long jobId,
    Long userId,
    String metricId,
    String metricName,
    double currentValue,
    double mean,
    double stddev,
    double deviation,
    String sensitivity,
    List<Double> recentHistory) {}
