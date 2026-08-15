package com.smartfirehub.ontology.binding;

import static org.jooq.impl.DSL.currentOffsetDateTime;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

// 데이터셋↔온톨로지 바인딩(N:1) 저장/조회. OntologyRepository와 동일한 plain-SQL DSL 패턴(codegen 비의존).
@Repository
@RequiredArgsConstructor
// RLS GUC 는 트랜잭션 시작 시점에만 주입되는데 호출자 DatasetOntologyService(bind/get)는 트랜잭션
// 경계를 만들지 않는다. 여기서 열지 않으면 bind 는 tenant_id NOT NULL 위반, get 은 조용히 0행이
// 된다. 지우지 말 것.
@Transactional
public class DatasetOntologyRepository {

  private final DSLContext dsl;

  private static final Table<?> DATASET_ONTOLOGY = table(name("dataset_ontology"));
  private static final Field<Long> DO_DATASET_ID = field(name("dataset_ontology", "dataset_id"), Long.class);
  // V101 에서 dataset_id 유니크가 (tenant_id, dataset_id) 로 접혔다. ON CONFLICT 추론 대상을
  // 새 인덱스와 일치시키지 않으면 "no unique or exclusion constraint matching" 런타임 오류가 난다.
  // 값은 컬럼 DEFAULT(GUC app.tenant_id)가 채우므로 INSERT 에서는 세팅하지 않는다.
  private static final Field<Long> DO_TENANT_ID = field(name("dataset_ontology", "tenant_id"), Long.class);
  private static final Field<Long> DO_ONTOLOGY_ID = field(name("dataset_ontology", "ontology_id"), Long.class);
  private static final Field<Long> DO_BOUND_BY = field(name("dataset_ontology", "bound_by"), Long.class);
  private static final Field<java.time.OffsetDateTime> DO_BOUND_AT =
      field(name("dataset_ontology", "bound_at"), java.time.OffsetDateTime.class);

  // 데이터셋을 온톨로지에 바인딩(UPSERT) — dataset_id UNIQUE라 재바인딩 시 ontology_id/bound_by 갱신.
  public void bind(long datasetId, long ontologyId, Long userId) {
    dsl.insertInto(DATASET_ONTOLOGY)
        .set(DO_DATASET_ID, datasetId)
        .set(DO_ONTOLOGY_ID, ontologyId)
        .set(DO_BOUND_BY, userId)
        .set(DO_BOUND_AT, currentOffsetDateTime())
        .onConflict(DO_TENANT_ID, DO_DATASET_ID)
        .doUpdate()
        .set(DO_ONTOLOGY_ID, ontologyId)
        .set(DO_BOUND_BY, userId)
        .set(DO_BOUND_AT, currentOffsetDateTime())
        .execute();
  }

  // 데이터셋에 바인딩된 온톨로지 id 조회(없으면 empty).
  public Optional<Long> findOntologyIdByDataset(long datasetId) {
    return dsl.select(DO_ONTOLOGY_ID).from(DATASET_ONTOLOGY).where(DO_DATASET_ID.eq(datasetId))
        .fetchOptional(r -> r.get(DO_ONTOLOGY_ID));
  }
}
