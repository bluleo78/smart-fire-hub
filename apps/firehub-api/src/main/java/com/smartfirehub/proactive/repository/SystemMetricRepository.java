package com.smartfirehub.proactive.repository;

import com.smartfirehub.securitylevel.access.DatasetVisibility;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 이상탐지 폴러가 쓰는 조회 저장소 — 폴링 대상 잡 조회와 시스템 메트릭(파이프라인 실행·데이터셋·활성 사용자) 집계.
 *
 * <p>구현은 {@code JooqSystemMetricRepository}(jOOQ/PostgreSQL) 이다 — WD-485. 다루는 테이블: {@code dataset},
 * {@code pipeline_execution}, {@code proactive_job}.
 */
public interface SystemMetricRepository {

  /**
   * 폴링 대상 잡 — 이상탐지 메트릭 설정을 담은 잡 한 건.
   *
   * @param config 잡 설정 JSONB 원문(없으면 null)
   */
  record PolledJob(Long id, Long userId, String config) {}

  /** 활성화된 ANOMALY/BOTH 트리거 잡을 모두 읽는다. */
  List<PolledJob> findEnabledAnomalyJobs();

  /** since 이후 생성된 파이프라인 실행 수. */
  int countPipelineExecutionsSince(LocalDateTime since);

  /** since 이후 생성된 파이프라인 실행 중 FAILED 상태의 수. */
  int countFailedPipelineExecutionsSince(LocalDateTime since);

  /** 가시성을 만족하는 데이터셋 수 — 가시성은 호출자가 DatasetAccessGuard 로 캡처해 넘기고, 여기서 "dataset" 이름 관례로 렌더한다. */
  int countDatasets(DatasetVisibility visibility);

  /** since 이후 파이프라인을 실행한 서로 다른 사용자 수. */
  int countDistinctPipelineExecutorsSince(LocalDateTime since);
}
