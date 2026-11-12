package com.smartfirehub.pipeline.repository.mybatis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.exception.SerializationException;
import com.smartfirehub.global.platformdb.mybatis.PlatformJson;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.pipeline.dto.TriggerEventResponse;
import com.smartfirehub.pipeline.repository.TriggerEventRepository;
import com.smartfirehub.pipeline.repository.mybatis.TriggerEventRows.EventRow;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/**
 * TriggerEventRepository 의 MyBatis 구현(WD-708) — {@code JooqTriggerEventRepository} 와 같은 의미.
 *
 * <p>트랜잭션 속성은 jOOQ 구현 그대로 클래스 단위 plain {@code @Transactional} 이다 — 배경 잡은 앰비언트 트랜잭션이 없고, 트랜잭션이 없으면
 * 테넌트 컨텍스트가 심기지 않아 정책이 0행이 된다.
 *
 * <p>detail 은 jOOQ 처럼 빈 맵·null 이면 NULL 로 저장하고, 읽을 때 NULL·깨진 JSON 은 빈 맵이다.
 */
@PlatformMyBatisRepository
@Transactional
public class MyBatisTriggerEventRepository implements TriggerEventRepository {

  private final TriggerEventMapper mapper;
  private final ObjectMapper objectMapper;

  public MyBatisTriggerEventRepository(TriggerEventMapper mapper, ObjectMapper objectMapper) {
    this.mapper = mapper;
    this.objectMapper = objectMapper;
  }

  @Override
  public Optional<Integer> findChainDepthOfExecution(Long pipelineId, Long executionId) {
    String raw = mapper.selectChainDepthRaw(pipelineId, executionId);
    if (raw == null) {
      return Optional.empty();
    }
    try {
      // 음수도 0 으로 다룬다(외부가 깊이 상한을 무력화하지 못하게 — 인터페이스 javadoc).
      return Optional.of(Math.max(0, Integer.parseInt(raw)));
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  @Override
  public Optional<LatestEvent> findLatestByTrigger(Long triggerId) {
    return Optional.ofNullable(mapper.selectLatest(triggerId));
  }

  @Override
  public void create(
      Long triggerId,
      Long pipelineId,
      Long executionId,
      String eventType,
      Map<String, Object> detail) {
    mapper.insert(triggerId, pipelineId, executionId, eventType, toJson(detail));
  }

  @Override
  public List<TriggerEventResponse> findByPipelineId(Long pipelineId, int limit) {
    return mapper.selectByPipeline(pipelineId, limit, 0).stream().map(this::toResponse).toList();
  }

  @Override
  public List<TriggerEventResponse> findByTriggerId(Long triggerId, int limit) {
    return mapper.selectByTrigger(triggerId, limit, 0).stream().map(this::toResponse).toList();
  }

  @Override
  public int deleteOlderThan(int days) {
    // jOOQ 판과 같은 자바 시각 기준.
    return mapper.deleteOlderThan(LocalDateTime.now().minusDays(days));
  }

  private TriggerEventResponse toResponse(EventRow r) {
    return new TriggerEventResponse(
        r.id(),
        r.triggerId(),
        r.triggerName(),
        r.eventType(),
        r.executionId(),
        parseJson(r.detailJson()),
        r.createdAt());
  }

  /** detail 읽기 — jOOQ 판처럼 NULL·깨진 JSON 은 빈 맵. */
  private Map<String, Object> parseJson(String json) {
    try {
      Map<String, Object> map = PlatformJson.toMap(objectMapper, json, "detail");
      return map != null ? map : Collections.emptyMap();
    } catch (SerializationException e) {
      return Collections.emptyMap();
    }
  }

  /** detail 쓰기 — jOOQ 판처럼 null·빈 맵·직렬화 실패는 NULL 로 저장한다. */
  private String toJson(Map<String, Object> map) {
    if (map == null || map.isEmpty()) {
      return null;
    }
    try {
      return PlatformJson.write(objectMapper, map, "detail");
    } catch (SerializationException e) {
      return null;
    }
  }
}
