package com.smartfirehub.ontology.graphread;

import static com.smartfirehub.jooq.Tables.DATASET;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.not;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.table;

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
 * 그러면 {@link #existsInvisibleSource} 가 false 가 되어 게이트가 <b>전부 열린다(fail-open)</b>. 판정 쿼리를
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
   * 이 온톨로지의 출처 중 <b>존재하는</b> 데이터셋인데 {@code visible} 을 만족하지 않는 것이 하나라도 있는가.
   *
   * <p>dataset 과 내부 조인하므로 삭제된 데이터셋(행은 있는데 dataset 이 없음)은 판정에서 빠진다 — 등급이 없기 때문이다(스펙 §4, 알려진 한계). 쿼리
   * 하나로 판정한다(N+1 없음).
   *
   * @param visible {@code Tables.DATASET.ID}·{@code Tables.DATASET.SECURITY_LEVEL_ID} 로 만든 열람 조건
   *     (DatasetAccessGuard#visibleCondition(Clearance, Field, Field))
   */
  @Transactional(readOnly = true)
  public boolean existsInvisibleSource(long ontologyId, Condition visible) {
    return dsl.fetchExists(
        selectOne()
            .from(GOS)
            .join(DATASET)
            .on(DATASET.ID.eq(GOS_DATASET_ID))
            .where(GOS_ONTOLOGY_ID.eq(ontologyId))
            .and(not(visible)));
  }
}
