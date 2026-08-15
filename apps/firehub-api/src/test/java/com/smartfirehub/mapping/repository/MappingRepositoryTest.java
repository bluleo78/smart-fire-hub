package com.smartfirehub.mapping.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.Optional;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

// V78 dataset_mapping 저장/조회/상태변경 검증. dataset_id는 FK 없음(가짜 id 사용), ontology_id는 시드 id=1.
//
// V102 로 dataset_mapping 에 RLS 가 걸린 뒤로는 이 테스트가 직접 쏘는 raw dsl 조회·삭제도 정책의 대상이다.
// GUC 는 트랜잭션이 열릴 때만 주입되므로 트랜잭션 밖에서는 조용히 0행이 된다. 검증 조회와 정리만
// 트랜잭션으로 감싼다 — mappingRepository 호출은 그 자신의 클래스 레벨 @Transactional 이 도는지가
// 검증 대상이므로 감싸지 않는다.
class MappingRepositoryTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private MappingRepository mappingRepository;
  @Autowired private TransactionTemplate tx;

  private void cleanup(long datasetId) {
    TenantRlsTestSupport.runInTenantTransaction(
        tx,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.deleteFrom(table(name("dataset_mapping")))
                .where(field(name("dataset_id"), Long.class).eq(datasetId))
                .execute());
  }

  @Test
  void 매핑_저장후_조회된다() {
    long datasetId = 997001L;
    String spec = "{\"entities\":[{\"entityType\":\"Incident\",\"nameColumn\":\"id\",\"properties\":[]}],\"relations\":[]}";
    mappingRepository.upsert(datasetId, 1L, spec, "draft", 42L);
    try {
      Optional<StoredMapping> found = mappingRepository.findByDataset(datasetId);
      assertThat(found).isPresent();
      assertThat(found.get().ontologyId()).isEqualTo(1L);
      assertThat(found.get().status()).isEqualTo("draft");
      // Postgres jsonb는 콜론/콤마 뒤 공백을 정규화해 저장하므로 공백 제거 후 비교.
      assertThat(found.get().specJson().replaceAll("\\s", "")).contains("\"entityType\":\"Incident\"");
    } finally {
      cleanup(datasetId);
    }
  }

  @Test
  void 재저장은_기존행을_덮어쓴다() {
    long datasetId = 997002L;
    mappingRepository.upsert(datasetId, 1L, "{\"entities\":[],\"relations\":[]}", "draft", 42L);
    mappingRepository.upsert(datasetId, 1L, "{\"entities\":[{\"entityType\":\"X\"}],\"relations\":[]}", "draft", 43L);
    try {
      int rows =
          TenantRlsTestSupport.runInTenantTransaction(
              tx,
              DEFAULT_TEST_TENANT_ID,
              () -> dsl.selectCount().from(table(name("dataset_mapping")))
                  .where(field(name("dataset_id"), Long.class).eq(datasetId)).fetchOne(0, int.class));
      assertThat(rows).isEqualTo(1); // UNIQUE(dataset_id)
      assertThat(mappingRepository.findByDataset(datasetId).get().specJson().replaceAll("\\s", ""))
          .contains("\"entityType\":\"X\"");
    } finally {
      cleanup(datasetId);
    }
  }

  @Test
  void updateStatus는_상태를_바꾼다() {
    long datasetId = 997003L;
    mappingRepository.upsert(datasetId, 1L, "{\"entities\":[],\"relations\":[]}", "draft", 42L);
    mappingRepository.updateStatus(datasetId, "active", 44L);
    try {
      assertThat(mappingRepository.findByDataset(datasetId).get().status()).isEqualTo("active");
    } finally {
      cleanup(datasetId);
    }
  }

  @Test
  void 미저장_데이터셋은_empty() {
    assertThat(mappingRepository.findByDataset(999998L)).isEmpty();
  }
}
