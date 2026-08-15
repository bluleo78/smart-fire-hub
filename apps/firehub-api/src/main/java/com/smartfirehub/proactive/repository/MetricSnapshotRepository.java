package com.smartfirehub.proactive.repository;

import com.smartfirehub.proactive.util.ProactiveTime;
import static com.smartfirehub.jooq.Tables.METRIC_SNAPSHOT;

import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 메트릭 스냅샷 저장소.
 *
 * <p>클래스 레벨 {@code @Transactional} 이 필요한 이유: V103 으로 {@code tenant_id} 가 생겼고
 * V104 에서 RLS 가 걸린다. 테넌트 값은 트랜잭션-로컬 GUC 인데 {@code MetricPollerService} 의
 * 스냅샷 저장·조회는 기존 {@code TransactionTemplate} 경계 <b>밖</b>에 있다(테넌트 컨텍스트는
 * 있으나 GUC 가 없다). 전파 REQUIRED 이므로 이미 트랜잭션 안인 호출의 동작은 불변이다.
 */
@Transactional
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
    LocalDateTime cutoff = ProactiveTime.nowUtc().minusDays(days);
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
