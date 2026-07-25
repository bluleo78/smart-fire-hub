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

// 데이터셋↔온톨로지 바인딩(N:1) 저장/조회. OntologyRepository와 동일한 plain-SQL DSL 패턴(codegen 비의존).
@Repository
@RequiredArgsConstructor
public class DatasetOntologyRepository {

  private final DSLContext dsl;

  private static final Table<?> DATASET_ONTOLOGY = table(name("dataset_ontology"));
  private static final Field<Long> DO_DATASET_ID = field(name("dataset_ontology", "dataset_id"), Long.class);
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
        .onConflict(DO_DATASET_ID)
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
