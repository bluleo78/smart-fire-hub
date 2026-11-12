package com.smartfirehub.proactive.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import com.smartfirehub.proactive.repository.MetricSnapshotRepository.MetricSnapshot;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * metric_snapshot MyBatis 매퍼(WD-709). SQL 은 {@code mybatis/proactive/MetricSnapshotMapper.xml}.
 * 테넌트는 정책(RLS)이 맡는다 — tenant_id 는 INSERT 값·WHERE 술어로 쓰지 않는다.
 */
@PlatformMapper
public interface MetricSnapshotMapper {

  int insert(
      @Param("jobId") Long jobId,
      @Param("metricId") String metricId,
      @Param("value") double value,
      @Param("collectedAt") LocalDateTime collectedAt);

  /** cutoff 이후 스냅샷을 최근 순(collected_at DESC, id DESC)으로. */
  List<MetricSnapshot> findRecent(
      @Param("jobId") Long jobId,
      @Param("metricId") String metricId,
      @Param("cutoff") LocalDateTime cutoff);

  /** 가장 최근 1건 — 없으면 null. */
  MetricSnapshot findLatest(@Param("jobId") Long jobId, @Param("metricId") String metricId);

  /** cutoff 이전 행을 지우고 지운 행 수를 돌려준다. */
  int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
