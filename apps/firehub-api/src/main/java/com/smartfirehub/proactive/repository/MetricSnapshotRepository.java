package com.smartfirehub.proactive.repository;

import static com.smartfirehub.jooq.Tables.METRIC_SNAPSHOT;

import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MetricSnapshotRepository {

  private final DSLContext dsl;

  public record MetricSnapshot(
      Long id, Long jobId, String metricId, double value, LocalDateTime collectedAt) {}

  /** Save a new metric snapshot */
  public void save(Long jobId, String metricId, double value, LocalDateTime collectedAt) {
    dsl.insertInto(METRIC_SNAPSHOT)
        .set(METRIC_SNAPSHOT.JOB_ID, jobId)
        .set(METRIC_SNAPSHOT.METRIC_ID, metricId)
        .set(METRIC_SNAPSHOT.VALUE, value)
        .set(METRIC_SNAPSHOT.COLLECTED_AT, collectedAt)
        .execute();
  }

  /** Find recent snapshots for a metric, ordered by collected_at DESC, limited to last N days */
  public List<MetricSnapshot> findRecent(Long jobId, String metricId, int days) {
    LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
    return dsl.selectFrom(METRIC_SNAPSHOT)
        .where(
            METRIC_SNAPSHOT
                .JOB_ID
                .eq(jobId)
                .and(METRIC_SNAPSHOT.METRIC_ID.eq(metricId))
                .and(METRIC_SNAPSHOT.COLLECTED_AT.ge(cutoff)))
        .orderBy(METRIC_SNAPSHOT.COLLECTED_AT.desc())
        .fetch(
            r ->
                new MetricSnapshot(
                    r.getId(), r.getJobId(), r.getMetricId(), r.getValue(), r.getCollectedAt()));
  }

  /** Find the latest snapshot for a metric */
  public MetricSnapshot findLatest(Long jobId, String metricId) {
    return dsl.selectFrom(METRIC_SNAPSHOT)
        .where(METRIC_SNAPSHOT.JOB_ID.eq(jobId).and(METRIC_SNAPSHOT.METRIC_ID.eq(metricId)))
        .orderBy(METRIC_SNAPSHOT.COLLECTED_AT.desc())
        .limit(1)
        .fetchOne(
            r ->
                new MetricSnapshot(
                    r.getId(), r.getJobId(), r.getMetricId(), r.getValue(), r.getCollectedAt()));
  }

  /** Delete snapshots older than the given datetime. Returns count of deleted rows. */
  public int deleteOlderThan(LocalDateTime cutoff) {
    return dsl.deleteFrom(METRIC_SNAPSHOT).where(METRIC_SNAPSHOT.COLLECTED_AT.lt(cutoff)).execute();
  }
}
