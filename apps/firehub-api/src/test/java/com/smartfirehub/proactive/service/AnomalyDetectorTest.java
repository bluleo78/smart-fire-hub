package com.smartfirehub.proactive.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.proactive.dto.AnomalyEvent;
import com.smartfirehub.proactive.repository.MetricSnapshotRepository.MetricSnapshot;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AnomalyDetectorTest {

  private final AnomalyDetector detector = new AnomalyDetector();

  private List<MetricSnapshot> generateHistory(double... values) {
    List<MetricSnapshot> snapshots = new ArrayList<>();
    for (int i = 0; i < values.length; i++) {
      snapshots.add(
          new MetricSnapshot(
              (long) i, 1L, "m1", values[i], LocalDateTime.now().minusHours(values.length - i)));
    }
    return snapshots;
  }

  @Test
  void detect_withInsufficientHistory_returnsEmpty() {
    List<MetricSnapshot> history = generateHistory(1, 2, 3, 4, 5, 6);
    Optional<AnomalyEvent> result =
        detector.detect(history, 100.0, "medium", 1L, 1L, "m1", "Metric 1");
    assertThat(result).isEmpty();
  }

  @Test
  void detect_normalValue_returnsEmpty() {
    // mean=10, stddev=~2.16, value=11 -> deviation ~0.46 < 2.0 (medium)
    List<MetricSnapshot> history = generateHistory(8, 9, 10, 11, 12, 10, 10);
    Optional<AnomalyEvent> result =
        detector.detect(history, 11.0, "medium", 1L, 1L, "m1", "Metric 1");
    assertThat(result).isEmpty();
  }

  @Test
  void detect_mediumSensitivity_2sigma() {
    // mean=10, stddev=~2.16, value=20 -> deviation ~4.63 >= 2.0 (medium)
    List<MetricSnapshot> history = generateHistory(8, 9, 10, 11, 12, 10, 10);
    Optional<AnomalyEvent> result =
        detector.detect(history, 20.0, "medium", 1L, 1L, "m1", "Metric 1");
    assertThat(result).isPresent();
    AnomalyEvent event = result.get();
    assertThat(event.sensitivity()).isEqualTo("medium");
    assertThat(event.deviation()).isGreaterThanOrEqualTo(2.0);
    assertThat(event.currentValue()).isEqualTo(20.0);
  }

  @Test
  void detect_lowSensitivity_3sigma() {
    // mean=10, stddev=1, deviation=2.1 < 3.0 (low) -> not detected
    List<MetricSnapshot> history = generateHistory(9, 10, 11, 9, 10, 11, 10);
    double mean = 10.0;
    double stddev = computeStddev(9, 10, 11, 9, 10, 11, 10);
    double targetDeviation = 2.1;
    double anomalyValue = mean + targetDeviation * stddev;

    Optional<AnomalyEvent> result =
        detector.detect(history, anomalyValue, "low", 1L, 1L, "m1", "Metric 1");
    assertThat(result).isEmpty();
  }

  @Test
  void detect_highSensitivity_1_5sigma() {
    // Same value as above but with high sensitivity (1.5σ) -> detected
    List<MetricSnapshot> history = generateHistory(9, 10, 11, 9, 10, 11, 10);
    double mean = 10.0;
    double stddev = computeStddev(9, 10, 11, 9, 10, 11, 10);
    double targetDeviation = 2.1;
    double anomalyValue = mean + targetDeviation * stddev;

    Optional<AnomalyEvent> result =
        detector.detect(history, anomalyValue, "high", 1L, 1L, "m1", "Metric 1");
    assertThat(result).isPresent();
    assertThat(result.get().deviation()).isGreaterThanOrEqualTo(1.5);
  }

  @Test
  void detect_zeroStddev_ignores() {
    List<MetricSnapshot> history = generateHistory(5, 5, 5, 5, 5, 5, 5);
    Optional<AnomalyEvent> result =
        detector.detect(history, 100.0, "high", 1L, 1L, "m1", "Metric 1");
    assertThat(result).isEmpty();
  }

  private double computeStddev(double... values) {
    double mean = 0;
    for (double v : values) mean += v;
    mean /= values.length;
    double variance = 0;
    for (double v : values) variance += (v - mean) * (v - mean);
    return Math.sqrt(variance / values.length);
  }
}
