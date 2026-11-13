package com.smartfirehub.job.repository.mybatis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.global.platformdb.mybatis.TiberoTextLimits;
import com.smartfirehub.job.dto.AsyncJobStatusResponse;
import com.smartfirehub.job.repository.AsyncJobRepository;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

/**
 * AsyncJobRepository 의 MyBatis 구현(WD-706) — {@code JooqAsyncJobRepository} 와 같은 의미. SQL 은 {@code
 * mybatis/job/AsyncJobMapper.xml}.
 *
 * <p>metadata 직렬화·역직렬화 규칙도 jOOQ 구현과 같다: 쓰기에서 null·빈 맵·직렬화 실패는 {@code "{}"}, 읽기에서 null·파싱 실패는 빈 맵(경고
 * 로그). updated_at 은 JVM 시각으로 쓴다.
 */
@PlatformMyBatisRepository
@Slf4j
// 배경 잡(JobRunr/@Async/@Scheduled)은 앰비언트 트랜잭션이 없다. 테넌트(PG GUC·Tibero 컨텍스트)는 트랜잭션 시작
// 시점에만 심기므로, 트랜잭션이 없으면 TenantContext 를 세워도 정책이 전 행을 차단한다.
// REQUIRED 라 서비스가 이미 연 트랜잭션에는 합류한다(기존 경로 동작 불변). jOOQ 구현에서 그대로 옮겼다 — 지우지 말 것.
@Transactional
public class MyBatisAsyncJobRepository implements AsyncJobRepository {

  private final AsyncJobMapper mapper;
  private final ObjectMapper objectMapper;

  public MyBatisAsyncJobRepository(AsyncJobMapper mapper, ObjectMapper objectMapper) {
    this.mapper = mapper;
    this.objectMapper = objectMapper;
  }

  @Override
  public void insert(
      String id,
      String jobType,
      String resource,
      String resourceId,
      Long userId,
      Map<String, Object> metadata) {
    mapper.insert(id, jobType, resource, resourceId, userId, toJson(metadata));
  }

  /**
   * 데이터셋 대상 작업(resource = 'dataset')의 데이터셋 id. 다른 자원의 작업이거나 id 가 숫자가 아니면 빈 값 — 엉뚱한 데이터셋으로 판정하거나 500
   * 이 나지 않게(jOOQ 구현과 같은 자바 쪽 판정).
   */
  @Override
  public Optional<Long> findDatasetResourceId(String jobId) {
    return Optional.ofNullable(mapper.selectDatasetResourceId(jobId))
        .filter(v -> v.matches("\\d+"))
        .map(Long::parseLong);
  }

  @Override
  public Optional<AsyncJobStatusResponse> findById(String id) {
    return Optional.ofNullable(mapper.selectById(id)).map(this::toResponse);
  }

  @Override
  public void updateStageAndProgress(
      String id, String stage, int progress, String message, Map<String, Object> metadata) {
    mapper.updateStageAndProgress(
        id, stage, progress, message, toJson(metadata), LocalDateTime.now());
  }

  @Override
  public void updateStageAndError(String id, String stage, int progress, String errorMessage) {
    // 실패 사유는 예외 메시지(외부 응답 포함)라 길이를 통제할 수 없다 — Tibero VARCHAR2(65532) 바이트 한도를 넘으면 UPDATE 가 실패해
    // 실패 기록이 사라지므로 앞부분으로 자른다(WD-728).
    mapper.updateStageAndError(
        id,
        stage,
        progress,
        TiberoTextLimits.truncateErrorMessage(errorMessage),
        LocalDateTime.now());
  }

  @Override
  public List<AsyncJobStatusResponse> findActiveByResource(
      String jobType, String resource, String resourceId) {
    return toResponses(mapper.selectActiveByResource(jobType, resource, resourceId));
  }

  @Override
  public List<AsyncJobStatusResponse> findActiveByUserAndJobType(Long userId, String jobType) {
    return toResponses(mapper.selectActiveByUserAndJobType(userId, jobType));
  }

  @Override
  public List<AsyncJobStatusResponse> findStaleJobs(LocalDateTime before) {
    return toResponses(mapper.selectStale(before));
  }

  @Override
  public int deleteOlderThan(LocalDateTime before) {
    return mapper.deleteOlderThan(before);
  }

  @Override
  public Optional<Long> findOwnerById(String id) {
    return Optional.ofNullable(mapper.selectOwnerById(id));
  }

  private List<AsyncJobStatusResponse> toResponses(List<AsyncJobRow> rows) {
    return rows.stream().map(this::toResponse).toList();
  }

  private AsyncJobStatusResponse toResponse(AsyncJobRow r) {
    return new AsyncJobStatusResponse(
        r.id(),
        r.jobType(),
        r.stage(),
        r.progress(),
        r.message(),
        parseMetadata(r.metadata()),
        r.errorMessage(),
        r.createdAt(),
        r.updatedAt(),
        r.userId());
  }

  /** JSON 문자열 → 맵. null·파싱 실패는 빈 맵(jOOQ 구현과 같다). */
  private Map<String, Object> parseMetadata(String json) {
    if (json == null) {
      return Collections.emptyMap();
    }
    try {
      return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
    } catch (JsonProcessingException e) {
      log.warn("Failed to parse async_job metadata JSON: {}", e.getMessage());
      return Collections.emptyMap();
    }
  }

  /** 맵 → JSON 문자열. null·빈 맵·직렬화 실패는 "{}"(jOOQ 구현과 같다). */
  private String toJson(Map<String, Object> metadata) {
    if (metadata == null || metadata.isEmpty()) {
      return "{}";
    }
    try {
      return objectMapper.writeValueAsString(metadata);
    } catch (JsonProcessingException e) {
      log.warn("Failed to serialize async_job metadata: {}", e.getMessage());
      return "{}";
    }
  }
}
