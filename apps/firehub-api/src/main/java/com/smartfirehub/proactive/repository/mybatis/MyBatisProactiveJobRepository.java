package com.smartfirehub.proactive.repository.mybatis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.dto.LockedRowVersion;
import com.smartfirehub.global.platformdb.mybatis.PlatformJson;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.proactive.dto.ProactiveJobResponse;
import com.smartfirehub.proactive.repository.ProactiveJobRepository;
import com.smartfirehub.proactive.repository.mybatis.ProactiveJobRows.JobInsert;
import com.smartfirehub.proactive.repository.mybatis.ProactiveJobRows.JobRow;
import com.smartfirehub.proactive.util.ProactiveTime;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/**
 * ProactiveJobRepository 의 MyBatis 구현(WD-709) — {@code JooqProactiveJobRepository} 와 같은 의미.
 *
 * <p>클래스 단위 {@code @Transactional} 은 jOOQ 구현 그대로이며 지우면 안 된다 — 트랜잭션 없이 도는 배경 경로(부팅 시 스케줄 재등록, 크론 발화
 * 콜백, {@code @Async} 실행기)에서는 RLS 컨텍스트가 심기지 않아 INSERT 가 NOT NULL 위반으로 깨지고 SELECT 는 0행이 된다(전파
 * REQUIRED 라 컨트롤러 경로는 불변).
 */
@PlatformMyBatisRepository
@Transactional
public class MyBatisProactiveJobRepository implements ProactiveJobRepository {

  private final ProactiveJobMapper mapper;
  private final ObjectMapper objectMapper;

  public MyBatisProactiveJobRepository(ProactiveJobMapper mapper, ObjectMapper objectMapper) {
    this.mapper = mapper;
    this.objectMapper = objectMapper;
  }

  @Override
  public List<ProactiveJobResponse> findByUserId(Long userId) {
    return mapper.findByUserId(userId).stream().map(this::toResponse).toList();
  }

  @Override
  public Optional<ProactiveJobResponse> findById(Long id, Long userId) {
    return Optional.ofNullable(mapper.findByIdAndUser(id, userId)).map(this::toResponse);
  }

  @Override
  public Optional<ProactiveJobResponse> findById(Long id) {
    return Optional.ofNullable(mapper.findById(id)).map(this::toResponse);
  }

  /** 지정한 테넌트의 활성 잡 — RLS 에 맡기지 않고 tenant_id 술어를 명시한다(jOOQ 구현과 같은 이유). */
  @Override
  public List<ProactiveJobResponse> findAllEnabled(long tenantId) {
    return mapper.findAllEnabled(tenantId).stream().map(this::toResponse).toList();
  }

  /**
   * enabled 가 null 이면 true, timezone 이 null 이면 Asia/Seoul, triggerType 이 null 이면 기본 트리거, config 가
   * null 이면 빈 맵.
   */
  @Override
  public Long create(
      Long userId,
      String name,
      String prompt,
      Long templateId,
      String cronExpression,
      String timezone,
      Boolean enabled,
      String triggerType,
      Map<String, Object> config) {
    JobInsert insert =
        new JobInsert(
            userId,
            name,
            prompt,
            templateId,
            cronExpression,
            timezone != null ? timezone : "Asia/Seoul",
            enabled != null ? enabled : true,
            triggerType != null ? triggerType : DEFAULT_TRIGGER_TYPE,
            PlatformJson.write(objectMapper, config != null ? config : Map.of(), "config"));
    mapper.insert(insert);
    return insert.getId();
  }

  @Override
  public Optional<LockedRowVersion> lockUpdatedAtById(Long id) {
    return Optional.ofNullable(mapper.lockVersionById(id))
        .map(row -> new LockedRowVersion(row.updatedAt()));
  }

  /**
   * 넘긴 필드만 고치고 수정 시각을 돌려준다 — 시각은 자바 UTC 값을 마이크로초로 잘라 바인딩하고, 고친 행이 있을 때 그 값을 돌려준다(저장 정밀도와 같아 다음 저장의
   * 기준 버전과 어긋나지 않는다). 고친 행이 없으면(남의 작업) null.
   */
  @Override
  public LocalDateTime update(
      Long id,
      Long userId,
      String name,
      String prompt,
      Long templateId,
      boolean clearTemplate,
      String cronExpression,
      String timezone,
      Boolean enabled,
      String triggerType,
      Map<String, Object> config) {
    LocalDateTime updatedAt = ProactiveTime.nowUtc().truncatedTo(ChronoUnit.MICROS);
    int updated =
        mapper.update(
            id,
            userId,
            updatedAt,
            name,
            prompt,
            templateId,
            clearTemplate,
            cronExpression,
            timezone,
            enabled,
            triggerType,
            config == null ? null : PlatformJson.write(objectMapper, config, "config"));
    return updated == 1 ? updatedAt : null;
  }

  /** 실행 기록 갱신 — updated_at 은 건드리지 않는다(WD-364). */
  @Override
  public void updateLastExecuted(
      Long id, LocalDateTime lastExecutedAt, LocalDateTime nextExecuteAt) {
    mapper.updateLastExecuted(id, lastExecutedAt, nextExecuteAt);
  }

  /** 다음 실행 예정 시각만 갱신 — updated_at 은 건드리지 않는다. */
  @Override
  public void updateNextExecuteAt(Long id, LocalDateTime nextExecuteAt) {
    mapper.updateNextExecuteAt(id, nextExecuteAt);
  }

  @Override
  public int delete(Long id, Long userId) {
    return mapper.delete(id, userId);
  }

  @Override
  public List<WorkspaceJobRow> findWorkspaceRows(long tenantId) {
    return mapper.findWorkspaceRows(tenantId);
  }

  @Override
  public Optional<WorkspaceJobRow> findWorkspaceRow(Long id, long tenantId) {
    return Optional.ofNullable(mapper.findWorkspaceRow(id, tenantId));
  }

  @Override
  public void disableInTenant(Long id, long tenantId) {
    mapper.disableInTenant(id, tenantId, ProactiveTime.nowUtc().truncatedTo(ChronoUnit.MICROS));
  }

  @Override
  public int deleteInTenant(Long id, long tenantId) {
    return mapper.deleteInTenant(id, tenantId);
  }

  /** config 가 비어 있으면(NULL) 빈 맵 — jOOQ 구현과 같다. */
  private ProactiveJobResponse toResponse(JobRow r) {
    Map<String, Object> config = PlatformJson.toMap(objectMapper, r.configJson(), "config");
    return new ProactiveJobResponse(
        r.id(),
        r.userId(),
        r.templateId(),
        r.templateName(),
        r.name(),
        r.prompt(),
        r.cronExpression(),
        r.timezone(),
        r.enabled(),
        r.triggerType(),
        config != null ? config : Map.of(),
        r.lastExecutedAt(),
        r.nextExecuteAt(),
        r.createdAt(),
        r.updatedAt(),
        null);
  }
}
