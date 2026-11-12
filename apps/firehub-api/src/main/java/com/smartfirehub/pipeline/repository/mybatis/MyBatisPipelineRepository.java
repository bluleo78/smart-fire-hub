package com.smartfirehub.pipeline.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.InChunks;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.pipeline.dto.PipelineResponse;
import com.smartfirehub.pipeline.repository.PipelineRepository;
import com.smartfirehub.pipeline.repository.mybatis.PipelineRows.PipelineInsert;
import com.smartfirehub.pipeline.repository.mybatis.PipelineRows.PipelineRow;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.transaction.annotation.Transactional;

/**
 * PipelineRepository 의 MyBatis 구현(WD-708) — {@code JooqPipelineRepository} 와 같은 의미.
 *
 * <p>트랜잭션 속성은 jOOQ 구현 그대로 클래스 단위 plain {@code @Transactional} 이다 — 배경 잡은 앰비언트 트랜잭션이 없고, 트랜잭션이 없으면
 * 테넌트 컨텍스트가 심기지 않아 정책이 0행이 된다.
 *
 * <p>RETURNING 이 없는 Tibero 를 위해 {@code save}·{@code update} 는 쓴 뒤 같은 트랜잭션에서 저장된 값을 다시 읽는다 — DB 에
 * 저장된(반올림된) 수정 시각을 돌려줘야 하는 WD-353 의 요구와도 맞다.
 */
@PlatformMyBatisRepository
@Transactional
public class MyBatisPipelineRepository implements PipelineRepository {

  private final PipelineMapper mapper;

  public MyBatisPipelineRepository(PipelineMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public List<PipelineResponse> findAll(int page, int size) {
    // jOOQ 판 offset(page * size)과 같은 값 — int 곱 오버플로를 피하려 long 으로 곱한다.
    return mapper.selectPage(size, (long) page * size).stream().map(this::toResponse).toList();
  }

  @Override
  public long count() {
    return mapper.count();
  }

  @Override
  public Optional<PipelineResponse> findById(Long id) {
    return Optional.ofNullable(mapper.selectById(id)).map(this::toResponse);
  }

  @Override
  public PipelineResponse save(String name, String description, Long createdBy) {
    PipelineInsert row = new PipelineInsert(name, description, createdBy);
    mapper.insert(row);
    // 방금 넣은 행이라 반드시 있다 — 스텝·트리거 수는 0, 만든이 이름은 조인(없으면 id 문자열).
    return toResponse(mapper.selectById(row.getId()));
  }

  @Override
  public LocalDateTime update(
      Long id, String name, String description, Boolean isActive, Long updatedBy) {
    // 수정 시각은 jOOQ 판과 같은 자바 시각을 바인딩하고, 돌려주는 값은 DB 에 저장된 값을 다시 읽는다(WD-353). 행이 없으면 null.
    mapper.update(id, name, description, isActive, updatedBy, LocalDateTime.now());
    return mapper.selectUpdatedAt(id);
  }

  @Override
  public void deleteById(Long id) {
    mapper.deleteById(id);
  }

  @Override
  public boolean existsByName(String name) {
    return mapper.countByName(name) > 0;
  }

  @Override
  public boolean existsById(Long id) {
    return mapper.countById(id) > 0;
  }

  @Override
  public Set<Long> findExistingIds(Collection<Long> ids) {
    if (ids.isEmpty()) {
      return Set.of();
    }
    // Tibero IN 목록 상한(1000)을 넘지 않게 조각으로 나눈다.
    return new HashSet<>(mapper.selectExistingIds(InChunks.of(ids)));
  }

  @Override
  public Optional<String> findNameById(Long id) {
    return Optional.ofNullable(mapper.selectName(id));
  }

  @Override
  public Optional<Long> findCreatedByIdById(Long id) {
    return Optional.ofNullable(mapper.selectCreatedBy(id));
  }

  @Override
  public Optional<LocalDateTime> findUpdatedAtById(Long id) {
    return Optional.ofNullable(mapper.selectUpdatedAt(id));
  }

  @Override
  public Optional<LockedPipeline> lockById(Long id) {
    return Optional.ofNullable(mapper.selectForUpdate(id));
  }

  @Override
  public Optional<Long> findUpdatedByById(Long id) {
    return Optional.ofNullable(mapper.selectUpdatedBy(id));
  }

  /** 만든이 이름이 없으면(사용자 행이 없을 때) 만든이 id 문자열로 대신한다 — jOOQ 판과 같다. */
  private PipelineResponse toResponse(PipelineRow r) {
    return new PipelineResponse(
        r.id(),
        r.name(),
        r.description(),
        r.isActive(),
        PipelineRows.displayName(r.userName(), r.createdBy()),
        r.stepCount(),
        r.triggerCount(),
        r.createdAt());
  }
}
