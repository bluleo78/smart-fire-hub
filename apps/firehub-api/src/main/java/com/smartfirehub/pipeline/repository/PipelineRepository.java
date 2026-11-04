package com.smartfirehub.pipeline.repository;

import com.smartfirehub.pipeline.dto.PipelineResponse;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 파이프라인 정의(pipeline) 저장·조회 리포지토리.
 *
 * <p>구현은 {@code JooqPipelineRepository}(jOOQ/PostgreSQL) 이다 — WD-485.
 */
public interface PipelineRepository {

  List<PipelineResponse> findAll(int page, int size);

  long count();

  Optional<PipelineResponse> findById(Long id);

  PipelineResponse save(String name, String description, Long createdBy);

  /**
   * 메타데이터를 고치고 새로 저장된 수정 시각을 돌려준다(WD-353).
   *
   * <p>Java 의 now() 가 아니라 DB 에 저장된 값(RETURNING)을 돌려준다 — PG timestamp 는 마이크로초로 반올림하므로 now() 를 그대로
   * 돌려주면 다음 저장의 기준 버전이 저장값과 어긋나 자기 저장과 충돌한다.
   */
  LocalDateTime update(Long id, String name, String description, Boolean isActive, Long updatedBy);

  void deleteById(Long id);

  boolean existsByName(String name);

  /**
   * 파이프라인이 (지금 테넌트에) 있는가 — 연쇄 트리거의 선행 파이프라인 존재 확인용(WD-218).
   *
   * <p>RLS 가 걸린 일반 조회라 다른 테넌트의 파이프라인은 "없음"과 똑같이 보인다(존재를 떠보는 오라클이 되지 않는다).
   */
  boolean existsById(Long id);

  /** 주어진 id 중 (지금 테넌트에) 실제로 있는 파이프라인 id 만 돌려준다 — 트리거 목록의 선행 없음 표시를 한 번의 조회로 계산하기 위함(WD-218). */
  Set<Long> findExistingIds(Collection<Long> ids);

  Optional<String> findNameById(Long id);

  Optional<Long> findCreatedByIdById(Long id);

  Optional<LocalDateTime> findUpdatedAtById(Long id);

  /** 행 잠금으로 읽은 파이프라인 — 이름(삭제 감사용)과 수정 시각(낙관적 잠금 비교용, nullable 컬럼이라 null 일 수 있다). */
  record LockedPipeline(String name, LocalDateTime updatedAt) {}

  /**
   * 파이프라인 행을 FOR UPDATE 로 잠그고 읽는다. 행이 없으면 빈 값 — 수정·삭제 경로의 존재 확인(404)이다. 잠금은 호출 트랜잭션 끝까지 유지된다.
   *
   * <ul>
   *   <li>수정(WD-353): 잠금 없이 수정 시각을 읽고 비교하면 동시에 들어온 두 저장이 같은 값을 읽어 둘 다 통과한다. 잠그면 나중 저장은 앞 저장의 커밋을
   *       기다렸다가 바뀐 값을 읽어 충돌로 거부된다. 수정 시각이 빈 옛 행도 행은 있으므로 "없음"으로 거부하지 않는다.
   *   <li>삭제와 실행 생성의 경쟁(WD-437): 실행 행 INSERT 는 FK 검사로 이 행에 FOR KEY SHARE 를 잡는데, 그것이 FOR UPDATE 와
   *       충돌한다. 그래서 삭제가 잠근 뒤 진행 중 실행을 판정하면 (1) 실행 생성이 먼저면 그 커밋을 기다렸다가 새 실행을 보고 409, (2) 삭제가 먼저면 실행
   *       INSERT 가 삭제 커밋까지 기다린 뒤 FK 위반으로 실패해 404 로 바뀐다(PipelineExecutionRepository#createExecution)
   *       — 어느 순서든 판정과 삭제 사이에 실행이 끼어들지 못한다.
   * </ul>
   */
  Optional<LockedPipeline> lockById(Long id);

  Optional<Long> findUpdatedByById(Long id);
}
