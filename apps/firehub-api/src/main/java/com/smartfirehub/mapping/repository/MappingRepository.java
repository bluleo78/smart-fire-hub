package com.smartfirehub.mapping.repository;

import static org.jooq.impl.DSL.currentOffsetDateTime;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

// 데이터셋 매핑 문서 저장/조회 — OntologyRepository와 동일 plain-SQL DSL(codegen 비의존).
@Repository
@RequiredArgsConstructor
public class MappingRepository {

  private final DSLContext dsl;

  private static final Table<?> DATASET_MAPPING = table(name("dataset_mapping"));
  private static final Field<Long> M_DATASET_ID = field(name("dataset_mapping", "dataset_id"), Long.class);
  private static final Field<Long> M_ONTOLOGY_ID = field(name("dataset_mapping", "ontology_id"), Long.class);
  private static final Field<JSONB> M_SPEC = field(name("dataset_mapping", "spec"), JSONB.class);
  private static final Field<String> M_STATUS = field(name("dataset_mapping", "status"), String.class);
  private static final Field<Long> M_UPDATED_BY = field(name("dataset_mapping", "updated_by"), Long.class);
  private static final Field<java.time.OffsetDateTime> M_UPDATED_AT =
      field(name("dataset_mapping", "updated_at"), java.time.OffsetDateTime.class);

  // 매핑 문서 UPSERT — dataset_id UNIQUE라 재저장 시 spec/status/updated_* 갱신.
  public void upsert(long datasetId, long ontologyId, String specJson, String status, Long userId) {
    JSONB spec = JSONB.valueOf(specJson);
    dsl.insertInto(DATASET_MAPPING)
        .set(M_DATASET_ID, datasetId)
        .set(M_ONTOLOGY_ID, ontologyId)
        .set(M_SPEC, spec)
        .set(M_STATUS, status)
        .set(M_UPDATED_BY, userId)
        .set(M_UPDATED_AT, currentOffsetDateTime())
        .onConflict(M_DATASET_ID)
        .doUpdate()
        .set(M_ONTOLOGY_ID, ontologyId)
        .set(M_SPEC, spec)
        .set(M_STATUS, status)
        .set(M_UPDATED_BY, userId)
        .set(M_UPDATED_AT, currentOffsetDateTime())
        .execute();
  }

  // 데이터셋 매핑 조회(없으면 empty).
  public Optional<StoredMapping> findByDataset(long datasetId) {
    return dsl.select(M_ONTOLOGY_ID, M_SPEC, M_STATUS)
        .from(DATASET_MAPPING)
        .where(M_DATASET_ID.eq(datasetId))
        .fetchOptional(r -> new StoredMapping(
            r.get(M_ONTOLOGY_ID), r.get(M_SPEC).data(), r.get(M_STATUS)));
  }

  // 상태만 전환(draft→active). updated_by/at 함께 갱신.
  public void updateStatus(long datasetId, String status, Long userId) {
    dsl.update(DATASET_MAPPING)
        .set(M_STATUS, status)
        .set(M_UPDATED_BY, userId)
        .set(M_UPDATED_AT, currentOffsetDateTime())
        .where(M_DATASET_ID.eq(datasetId))
        .execute();
  }
}
