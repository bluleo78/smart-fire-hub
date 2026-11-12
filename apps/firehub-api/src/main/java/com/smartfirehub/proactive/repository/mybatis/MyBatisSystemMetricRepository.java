package com.smartfirehub.proactive.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.proactive.repository.SystemMetricRepository;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.securitylevel.repository.mybatis.MyBatisDatasetVisibility;
import java.time.LocalDateTime;
import java.util.List;

/**
 * SystemMetricRepository 의 MyBatis 구현(WD-709) — {@code JooqSystemMetricRepository} 와 같은 의미.
 *
 * <p>jOOQ 구현처럼 의도적으로 클래스 레벨 {@code @Transactional} 을 두지 않는다. 호출자({@code MetricPollerService})의
 * {@code TransactionTemplate} 이 DB 구간에만 좁게 트랜잭션을 열어 테넌트 컨텍스트를 심으므로, 그 경계 밖에서 부르면 RLS 가 모든 행을 가려 0 이
 * 집계된다.
 */
@PlatformMyBatisRepository
public class MyBatisSystemMetricRepository implements SystemMetricRepository {

  private final SystemMetricMapper mapper;

  public MyBatisSystemMetricRepository(SystemMetricMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public List<PolledJob> findEnabledAnomalyJobs() {
    return mapper.findEnabledAnomalyJobs();
  }

  @Override
  public int countPipelineExecutionsSince(LocalDateTime since) {
    return mapper.countPipelineExecutionsSince(since);
  }

  @Override
  public int countFailedPipelineExecutionsSince(LocalDateTime since) {
    return mapper.countFailedPipelineExecutionsSince(since);
  }

  /** 가시성은 공용 조각(DatasetVisibility.visible)이 렌더한다 — 값 → 평평한 파라미터 변환은 MyBatisDatasetVisibility. */
  @Override
  public int countDatasets(DatasetVisibility visibility) {
    return mapper.countDatasets(MyBatisDatasetVisibility.params(visibility));
  }

  @Override
  public int countDistinctPipelineExecutorsSince(LocalDateTime since) {
    return mapper.countDistinctPipelineExecutorsSince(since);
  }
}
