package com.smartfirehub.proactive.repository;

import static org.jooq.impl.DSL.*;

import com.smartfirehub.proactive.dto.AnomalyEvent;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

/**
 * 이상 탐지 이벤트 저장/조회 Repository. anomaly_event 테이블은 jOOQ 코드젠 대상이 아니므로 raw DSL(table/field 함수)을 사용한다.
 * proactive_job과 1:N 관계 — 하나의 작업에서 여러 이벤트가 발생할 수 있다.
 */
@Repository
@RequiredArgsConstructor
public class AnomalyEventRepository {

  private final DSLContext dsl;

  /**
   * 이상 탐지 이벤트를 DB에 저장한다. detected_at은 현재 시각으로 자동 설정된다.
   *
   * @param event 저장할 이상 탐지 이벤트 DTO
   */
  public void save(AnomalyEvent event) {
    dsl.insertInto(table("anomaly_event"))
        .set(field("job_id"), event.jobId())
        .set(field("metric_id"), event.metricId())
        .set(field("metric_name"), event.metricName())
        .set(field("current_value"), event.currentValue())
        .set(field("mean"), event.mean())
        .set(field("stddev"), event.stddev())
        .set(field("deviation"), event.deviation())
        .set(field("sensitivity"), event.sensitivity())
        .set(field("detected_at"), LocalDateTime.now())
        .execute();
  }

  /**
   * 특정 작업의 이상 탐지 이벤트를 최근 순으로 조회한다. idx_anomaly_event_job_detected 인덱스를 통해 효율적으로 조회한다.
   *
   * @param jobId 조회할 proactive_job ID
   * @param limit 최대 반환 건수
   * @return 이벤트 목록 (detected_at DESC 정렬)
   */
  public List<AnomalyEventRecord> findByJobId(Long jobId, int limit) {
    return dsl.select(
            field("id", Long.class),
            field("job_id", Long.class),
            field("metric_id", String.class),
            field("metric_name", String.class),
            field("current_value", Double.class),
            field("mean", Double.class),
            field("stddev", Double.class),
            field("deviation", Double.class),
            field("sensitivity", String.class),
            field("detected_at", LocalDateTime.class))
        .from(table("anomaly_event"))
        .where(field("job_id").eq(jobId))
        .orderBy(field("detected_at").desc())
        .limit(limit)
        .fetch(this::toRecord);
  }

  /**
   * jOOQ Record를 AnomalyEventRecord로 변환하는 매핑 메서드.
   *
   * @param r jOOQ 조회 결과 레코드
   * @return 변환된 AnomalyEventRecord
   */
  private AnomalyEventRecord toRecord(Record r) {
    return new AnomalyEventRecord(
        r.get(field("id", Long.class)),
        r.get(field("job_id", Long.class)),
        r.get(field("metric_id", String.class)),
        r.get(field("metric_name", String.class)),
        r.get(field("current_value", Double.class)),
        r.get(field("mean", Double.class)),
        r.get(field("stddev", Double.class)),
        r.get(field("deviation", Double.class)),
        r.get(field("sensitivity", String.class)),
        r.get(field("detected_at", LocalDateTime.class)));
  }

  /** 이상 탐지 이벤트 응답 DTO. API 응답 및 서비스 레이어 간 데이터 전달에 사용된다. */
  public record AnomalyEventRecord(
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
