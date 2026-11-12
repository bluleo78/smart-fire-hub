package com.smartfirehub.proactive.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.proactive.repository.MetricSnapshotRepository;
import com.smartfirehub.proactive.util.ProactiveTime;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

/**
 * MetricSnapshotRepository 의 MyBatis 구현(WD-709) — {@code JooqMetricSnapshotRepository} 와 같은 의미.
 *
 * <p>클래스 단위 {@code @Transactional} 은 jOOQ 구현 그대로이며 지우면 안 된다 — 스냅샷 저장·조회가 호출자의 트랜잭션 경계 밖이라, 없으면 RLS
 * 컨텍스트가 심기지 않는다.
 */
@PlatformMyBatisRepository
@Transactional
public class MyBatisMetricSnapshotRepository implements MetricSnapshotRepository {

  private final MetricSnapshotMapper mapper;

  public MyBatisMetricSnapshotRepository(MetricSnapshotMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public void save(Long jobId, String metricId, double value, LocalDateTime collectedAt) {
    mapper.insert(jobId, metricId, value, collectedAt);
  }

  /** 기준 시각은 자바 UTC 현재 시각에서 days 일 전(jOOQ 구현과 같다). */
  @Override
  public List<MetricSnapshot> findRecent(Long jobId, String metricId, int days) {
    return mapper.findRecent(jobId, metricId, ProactiveTime.nowUtc().minusDays(days));
  }

  @Override
  public MetricSnapshot findLatest(Long jobId, String metricId) {
    return mapper.findLatest(jobId, metricId);
  }

  @Override
  public int deleteOlderThan(LocalDateTime cutoff) {
    return mapper.deleteOlderThan(cutoff);
  }
}
