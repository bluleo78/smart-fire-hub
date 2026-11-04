package com.smartfirehub.proactive.repository;

import com.smartfirehub.proactive.dto.AnomalyEvent;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 이상 탐지 이벤트 저장/조회 Repository. anomaly_event 테이블은 jOOQ 코드젠 대상이 아니므로 raw DSL(table/field 함수)을 사용한다.
 * proactive_job과 1:N 관계 — 하나의 작업에서 여러 이벤트가 발생할 수 있다.
 *
 * <p>구현은 {@code JooqAnomalyEventRepository}(jOOQ/PostgreSQL) 이다 — WD-485. 다루는 테이블: {@code
 * anomaly_event}.
 */
public interface AnomalyEventRepository {

  /**
   * 이상 탐지 이벤트를 DB에 저장한다. detected_at은 현재 시각으로 자동 설정된다.
   *
   * @param event 저장할 이상 탐지 이벤트 DTO
   */
  void save(AnomalyEvent event);

  /**
   * 특정 작업의 이상 탐지 이벤트를 최근 순으로 조회한다. idx_anomaly_event_job_detected 인덱스를 통해 효율적으로 조회한다.
   *
   * @param jobId 조회할 proactive_job ID
   * @param limit 최대 반환 건수
   * @return 이벤트 목록 (detected_at DESC 정렬)
   */
  List<AnomalyEventRecord> findByJobId(Long jobId, int limit);

  /** 이상 탐지 이벤트 응답 DTO. API 응답 및 서비스 레이어 간 데이터 전달에 사용된다. */
  record AnomalyEventRecord(
      Long id,
      Long jobId,
      String metricId,
      String metricName,
      double currentValue,
      double mean,
      double stddev,
      double deviation,
      String sensitivity,
      LocalDateTime detectedAt) {}
}
