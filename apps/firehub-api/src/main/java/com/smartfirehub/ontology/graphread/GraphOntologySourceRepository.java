package com.smartfirehub.ontology.graphread;

import static com.smartfirehub.jooq.Tables.DATASET;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.not;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.table;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 지식그래프 출처(graph_ontology_source, V135) 저장·판정 쿼리. OntologyRepository 와 같은 plain-SQL DSL(codegen
 * 비의존).
 *
 * <p>클래스 레벨 @Transactional 은 지우지 말 것. RLS GUC 는 트랜잭션 시작 시점에만 주입되는데, 판정 쿼리가 트랜잭션 없이 돌면 출처가 0행으로 보인다.
 * 그러면 {@link #existsUnreadableSource} 가 false 가 되어 게이트가 <b>전부 열린다(fail-open)</b>. 판정 쿼리를
 * GraphReadGate 가 아니라 이 별도 빈에 두는 이유도 같다 — 같은 클래스 안의 자기 호출은 프록시를 우회해 트랜잭션이 걸리지 않는다.
 */
@Repository
@RequiredArgsConstructor
@Transactional
public class GraphOntologySourceRepository {

  private final DSLContext dsl;

  private static final Table<?> GOS = table(name("graph_ontology_source"));
  private static final Field<Long> GOS_ONTOLOGY_ID =
      field(name("graph_ontology_source", "ontology_id"), Long.class);
  private static final Field<Long> GOS_DATASET_ID =
      field(name("graph_ontology_source", "dataset_id"), Long.class);

  /**
   * (온톨로지, 데이터셋) 출처를 남긴다. 이미 있으면 아무것도 하지 않는다(멱등). tenant_id 는 컬럼 DEFAULT(GUC)가 채운다. 호출자(연결·매핑 저장)의
   * 트랜잭션에 합류하므로, 바인딩이 롤백되면 출처도 함께 롤백된다.
   */
  public void record(long ontologyId, long datasetId) {
    dsl.insertInto(GOS)
        .set(GOS_ONTOLOGY_ID, ontologyId)
        .set(GOS_DATASET_ID, datasetId)
        .onConflictDoNothing()
        .execute();
  }

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
   * @param visible {@code Tables.DATASET.ID}·{@code Tables.DATASET.SECURITY_LEVEL_ID} 로 만든 열람 조건
   *     (DatasetAccessGuard#visibleCondition(Clearance, Field, Field))
   * @param deletedSourceReadable 삭제된 출처를 막지 않을지(테넌트 관리자면 true)
   */
  @Transactional(readOnly = true)
  public boolean existsUnreadableSource(
      long ontologyId, Condition visible, boolean deletedSourceReadable) {
    // 존재하는 출처는 열람 조건으로 — isNotNull 을 명시해 삭제 행의 NULL 비교가 not(visible) 에 섞이지 않게 한다.
    Condition invisibleExisting = DATASET.ID.isNotNull().and(not(visible));
    Condition blocking =
        deletedSourceReadable ? invisibleExisting : invisibleExisting.or(DATASET.ID.isNull());
    return dsl.fetchExists(
        selectOne()
            .from(GOS)
            .leftJoin(DATASET)
            .on(DATASET.ID.eq(GOS_DATASET_ID))
            .where(GOS_ONTOLOGY_ID.eq(ontologyId))
            .and(blocking));
  }

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
  @Transactional(readOnly = true)
  public List<Long> findDatasetsWithGraphHistory(long tenantId, List<Long> datasetIds) {
    if (datasetIds.isEmpty()) {
      return List.of();
    }
    Long[] ids = datasetIds.toArray(Long[]::new);
    return dsl.fetch(
            "SELECT dataset_id FROM graph_ontology_source WHERE tenant_id = ? AND dataset_id = ANY(?)"
                + " UNION"
                + " SELECT dataset_id FROM dataset_graph_ingest WHERE tenant_id = ? AND dataset_id = ANY(?)"
                + " ORDER BY dataset_id",
            tenantId,
            ids,
            tenantId,
            ids)
        .getValues(0, Long.class);
  }
}
