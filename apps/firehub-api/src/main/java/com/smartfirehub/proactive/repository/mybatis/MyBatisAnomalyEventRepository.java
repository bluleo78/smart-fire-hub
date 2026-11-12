package com.smartfirehub.proactive.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.proactive.dto.AnomalyEvent;
import com.smartfirehub.proactive.repository.AnomalyEventRepository;
import com.smartfirehub.proactive.util.ProactiveTime;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

/**
 * AnomalyEventRepository 의 MyBatis 구현(WD-709) — {@code JooqAnomalyEventRepository} 와 같은 의미.
 *
 * <p>클래스 단위 {@code @Transactional} 은 jOOQ 구현 그대로이며 지우면 안 된다 — 쓰기 호출자가 트랜잭션 없는 {@code @Async} 리스너라,
 * 트랜잭션이 없으면 RLS 컨텍스트가 심기지 않아 INSERT 의 tenant_id DEFAULT 가 비어 실패한다.
 */
@PlatformMyBatisRepository
@Transactional
public class MyBatisAnomalyEventRepository implements AnomalyEventRepository {

  private final AnomalyEventMapper mapper;

  public MyBatisAnomalyEventRepository(AnomalyEventMapper mapper) {
    this.mapper = mapper;
  }

  /** 이벤트를 저장한다 — detected_at 은 자바 UTC 현재 시각(jOOQ 구현과 같은 기준). */
  @Override
  public void save(AnomalyEvent event) {
    mapper.insert(
        event.jobId(),
        event.metricId(),
        event.metricName(),
        event.currentValue(),
        event.mean(),
        event.stddev(),
        event.deviation(),
        event.sensitivity(),
        ProactiveTime.nowUtc());
  }

  @Override
  public List<AnomalyEventRecord> findByJobId(Long jobId, int limit) {
    return mapper.findByJobId(jobId, limit, 0);
  }
}
