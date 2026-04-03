package com.smartfirehub.proactive.service;

import com.smartfirehub.proactive.dto.AnomalyEvent;
import com.smartfirehub.proactive.repository.MetricSnapshotRepository.MetricSnapshot;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class AnomalyDetector {

  private static final int MIN_HISTORY_COUNT = 7;

  private static final Map<String, Double> SENSITIVITY_THRESHOLDS =
      Map.of("low", 3.0, "medium", 2.0, "high", 1.5);

  public Optional<AnomalyEvent> detect(
      List<MetricSnapshot> history,
      double currentValue,
      String sensitivity,
      Long jobId,
      Long userId,
      String metricId,
      String metricName) {

    if (history.size() < MIN_HISTORY_COUNT) {
      return Optional.empty();
    }

    double[] values = history.stream().mapToDouble(MetricSnapshot::value).toArray();

    double mean = 0;
    for (double v : values) {
      mean += v;
    }
    mean /= values.length;

    double variance = 0;
    for (double v : values) {
      variance += (v - mean) * (v - mean);
    }
    variance /= values.length;
    double stddev = Math.sqrt(variance);

    if (stddev < 1e-9) {
      return Optional.empty();
    }

    double deviation = Math.abs(currentValue - mean) / stddev;

    double threshold = SENSITIVITY_THRESHOLDS.getOrDefault(sensitivity.toLowerCase(), 2.0);

    if (deviation >= threshold) {
      List<Double> recentHistory = history.stream().map(MetricSnapshot::value).toList();

      return Optional.of(
          new AnomalyEvent(
              jobId,
              userId,
              metricId,
              metricName,
              currentValue,
              mean,
              stddev,
              deviation,
              sensitivity,
              recentHistory));
    }

    return Optional.empty();
  }
}
