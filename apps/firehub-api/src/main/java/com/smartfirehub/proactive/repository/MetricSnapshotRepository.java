package com.smartfirehub.proactive.repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 메트릭 스냅샷 저장소.
 *
 * <p>구현은 {@code JooqMetricSnapshotRepository}(jOOQ/PostgreSQL) 이다 — WD-485. 다루는 테이블: {@code
 * metric_snapshot}.
 */
public interface MetricSnapshotRepository {

  record MetricSnapshot(
      Long id, Long jobId, String metricId, double value, LocalDateTime collectedAt) {}

  /** Save a new metric snapshot */
  void save(Long jobId, String metricId, double value, LocalDateTime collectedAt);

  /** Find recent snapshots for a metric, ordered by collected_at DESC, limited to last N days */
  List<MetricSnapshot> findRecent(Long jobId, String metricId, int days);

  /** Find the latest snapshot for a metric */
  MetricSnapshot findLatest(Long jobId, String metricId);

  /** Delete snapshots older than the given datetime. Returns count of deleted rows. */
  int deleteOlderThan(LocalDateTime cutoff);
}
