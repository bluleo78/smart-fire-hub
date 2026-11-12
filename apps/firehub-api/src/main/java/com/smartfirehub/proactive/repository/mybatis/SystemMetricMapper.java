package com.smartfirehub.proactive.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import com.smartfirehub.proactive.repository.SystemMetricRepository.PolledJob;
import com.smartfirehub.securitylevel.repository.mybatis.MyBatisDatasetVisibility.VisibilityParams;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 이상탐지 폴러용 조회 MyBatis 매퍼(WD-709). SQL 은 {@code mybatis/proactive/SystemMetricMapper.xml}.
 * proactive_job·dataset· pipeline_execution 은 RLS 테이블이라 tenant_id 를 술어로 쓰지 않는다 — 호출자 트랜잭션의 테넌트
 * 컨텍스트가 거른다.
 */
@PlatformMapper
public interface SystemMetricMapper {

  List<PolledJob> findEnabledAnomalyJobs();

  int countPipelineExecutionsSince(@Param("since") LocalDateTime since);

  int countFailedPipelineExecutionsSince(@Param("since") LocalDateTime since);

  /** 가시성 조각(visible)을 dataset 이름 관례(별칭 없음)로 건다. */
  int countDatasets(@Param("vis") VisibilityParams vis);

  int countDistinctPipelineExecutorsSince(@Param("since") LocalDateTime since);
}
