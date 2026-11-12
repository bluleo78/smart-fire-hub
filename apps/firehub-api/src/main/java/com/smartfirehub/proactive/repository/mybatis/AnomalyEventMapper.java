package com.smartfirehub.proactive.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import com.smartfirehub.proactive.repository.AnomalyEventRepository.AnomalyEventRecord;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * anomaly_event MyBatis 매퍼(WD-709). SQL 은 {@code mybatis/proactive/AnomalyEventMapper.xml}. 테넌트는
 * 정책(RLS)이 맡는다 — tenant_id 는 INSERT 값·WHERE 술어로 쓰지 않는다(열 DEFAULT 가 채운다).
 */
@PlatformMapper
public interface AnomalyEventMapper {

  /** 이벤트 한 건을 넣는다. detected_at 은 jOOQ 구현과 같이 자바 UTC 값을 바인딩한다. */
  int insert(
      @Param("jobId") Long jobId,
      @Param("metricId") String metricId,
      @Param("metricName") String metricName,
      @Param("currentValue") double currentValue,
      @Param("mean") double mean,
      @Param("stddev") double stddev,
      @Param("deviation") double deviation,
      @Param("sensitivity") String sensitivity,
      @Param("detectedAt") LocalDateTime detectedAt);

  /** 작업의 이벤트를 최근 순(detected_at DESC, id DESC)으로 limit 건. */
  List<AnomalyEventRecord> findByJobId(
      @Param("jobId") Long jobId, @Param("limit") int limit, @Param("offset") int offset);
}
