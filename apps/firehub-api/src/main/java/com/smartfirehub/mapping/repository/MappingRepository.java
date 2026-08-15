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
import org.springframework.transaction.annotation.Transactional;

// 데이터셋 매핑 문서 저장/조회 — OntologyRepository와 동일 plain-SQL DSL(codegen 비의존).
@Repository
@RequiredArgsConstructor
// RLS GUC 는 트랜잭션 시작 시점에만 주입되는데 호출자 MappingService.get 은 트랜잭션 경계를 만들지
// 않는다. 여기서 열지 않으면 조회가 조용히 0행 — save/activate 는 서비스 트랜잭션이라 쓰기만 성공하고
// "저장했는데 UI 에는 미매핑" 으로 나타난다. 지우지 말 것.
@Transactional
public class MappingRepository {

  private final DSLContext dsl;

  private static final Table<?> DATASET_MAPPING = table(name("dataset_mapping"));
  private static final Field<Long> M_DATASET_ID = field(name("dataset_mapping", "dataset_id"), Long.class);
  // V101 에서 dataset_id 유니크가 (tenant_id, dataset_id) 로 접혔다. ON CONFLICT 추론 대상도
  // 같은 컬럼 집합이어야 하므로 tenant_id 를 참조한다. 값 자체는 INSERT 에서 세팅하지 않고
  // 컬럼 DEFAULT(GUC app.tenant_id)가 채운다 — 앱이 테넌트를 직접 쓰지 않는다는 원칙 유지.
  private static final Field<Long> M_TENANT_ID = field(name("dataset_mapping", "tenant_id"), Long.class);
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
        .onConflict(M_TENANT_ID, M_DATASET_ID)
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
