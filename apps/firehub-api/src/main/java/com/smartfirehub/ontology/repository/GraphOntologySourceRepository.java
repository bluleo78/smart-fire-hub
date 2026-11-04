package com.smartfirehub.ontology.repository;

import com.smartfirehub.securitylevel.access.DatasetVisibility;
import java.util.List;

/**
 * 지식그래프 출처(graph_ontology_source, V135) 저장·판정 쿼리. OntologyRepository 와 같은 plain-SQL DSL(codegen
 * 비의존).
 *
 * <p>클래스 레벨 @Transactional 은 지우지 말 것. RLS GUC 는 트랜잭션 시작 시점에만 주입되는데, 판정 쿼리가 트랜잭션 없이 돌면 출처가 0행으로 보인다.
 * 그러면 {@link #existsUnreadableSource} 가 false 가 되어 게이트가 <b>전부 열린다(fail-open)</b>. 판정 쿼리를
 * GraphReadGate 가 아니라 이 별도 빈에 두는 이유도 같다 — 같은 클래스 안의 자기 호출은 프록시를 우회해 트랜잭션이 걸리지 않는다.
 *
 * <p>구현은 {@code JooqGraphOntologySourceRepository}(jOOQ/PostgreSQL) 이다 — WD-485.
 */
public interface GraphOntologySourceRepository {

  /**
   * (온톨로지, 데이터셋) 출처를 남긴다. 이미 있으면 아무것도 하지 않는다(멱등). tenant_id 는 컬럼 DEFAULT(GUC)가 채운다. 호출자(연결·매핑 저장)의
   * 트랜잭션에 합류하므로, 바인딩이 롤백되면 출처도 함께 롤백된다.
   */
  void record(long ontologyId, long datasetId);

  /** 이 온톨로지의 출처 데이터셋 id(삭제된 데이터셋 포함) — 스마트 작업 리포트 등급 기록용(WD-198). */
  List<Long> findDatasetIds(long ontologyId);

  /**
   * 이 온톨로지의 출처 중 읽기를 막는 것이 하나라도 있는가. 막는 출처는 둘이다.
   *
   * <ul>
   *   <li>존재하는 데이터셋인데 {@code visible} 을 만족하지 않는 것.
   *   <li>삭제된 데이터셋(출처 행은 있는데 dataset 이 없음) — {@code deletedSourceReadable} 이 false 일 때만. 삭제된 데이터셋은
   *       등급이 없어 열람 조건으로 판정할 수 없는데, 그 내용은 Neo4j 그래프에 남아 있다. 그래서 테넌트 관리자만 읽게 한다(스펙 §4).
   * </ul>
   *
   * <p>dataset 과 LEFT JOIN 해 두 조건을 쿼리 하나로 판정한다(N+1 없음).
   *
   * @param visibility 조회자 열람 가시성 — 이 메서드가 {@code Tables.DATASET.ID}·{@code
   *     Tables.DATASET.SECURITY_LEVEL_ID} 컬럼에 걸어 렌더한다(서비스가 생성 테이블을 참조하지 않게, WD-485)
   * @param deletedSourceReadable 삭제된 출처를 막지 않을지(테넌트 관리자면 true)
   */
  boolean existsUnreadableSource(
      long ontologyId, DatasetVisibility visibility, boolean deletedSourceReadable);

  /**
   * 주어진 데이터셋 중 지식그래프에 내용이 쓰였을 수 있는 것(출처 기록 graph_ontology_source 또는 적재 이력 dataset_graph_ingest 가 있는
   * 것)의 id 를 오름차순으로 돌려준다. 외부 벡터 정리(AiVectorPurgeService)가 "GraphRAG 기적재분 — 자동 회수 불가, 수동 정리 대상"(스펙
   * §4.3·§7.5)을 표시하는 데 쓴다.
   *
   * <p>두 테이블을 합치는 이유: 적재 이력은 문서 GraphRAG 적재만 남고, 표 투영·추론 표본은 출처 기록에만 남는다. 출처 기록은 연결·매핑 저장 시점에 남으므로
   * 실제 적재보다 넓을 수 있다 — 경고 용도라 넓게(안전 쪽) 잡는다. 상태(SUCCESS/FAILED)도 가리지 않는다 — 실패한 적재도 일부를 썼을 수 있다.
   *
   * <p>RLS 와 별개로 {@code tenant_id} 를 명시한다(소유자 커넥션에서 불려도 남의 테넌트 이력을 섞지 않게).
   */
  List<Long> findDatasetsWithGraphHistory(long tenantId, List<Long> datasetIds);
}
