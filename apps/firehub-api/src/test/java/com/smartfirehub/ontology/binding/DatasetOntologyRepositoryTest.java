package com.smartfirehub.ontology.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.support.IntegrationTestBase;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

// V77 마이그레이션이 dataset_ontology 테이블과 ontology IDENTITY를 정상 생성했는지 확인한다.
class DatasetOntologyRepositoryTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private DatasetOntologyRepository bindingRepository;

  @Test
  void 바인딩_저장후_조회된다() {
    long datasetId = 999001L; // 실제 dataset 행 불필요(FK 없음, audit 패턴).
    bindingRepository.bind(datasetId, 1L, 42L);
    try {
      assertThat(bindingRepository.findOntologyIdByDataset(datasetId)).contains(1L);
    } finally {
      dsl.deleteFrom(table(name("dataset_ontology")))
          .where(field(name("dataset_id"), Long.class).eq(datasetId))
          .execute();
    }
  }

  @Test
  void 재바인딩은_기존값을_덮어쓴다() {
    long datasetId = 999002L;
    bindingRepository.bind(datasetId, 1L, 42L);
    bindingRepository.bind(datasetId, 1L, 43L); // 같은 온톨로지 재바인딩(UPSERT 경로).
    try {
      assertThat(bindingRepository.findOntologyIdByDataset(datasetId)).contains(1L);
      int rows =
          dsl.selectCount().from(table(name("dataset_ontology")))
              .where(field(name("dataset_id"), Long.class).eq(datasetId))
              .fetchOne(0, int.class);
      assertThat(rows).isEqualTo(1); // UNIQUE(dataset_id) — 중복 행 없음.
    } finally {
      dsl.deleteFrom(table(name("dataset_ontology")))
          .where(field(name("dataset_id"), Long.class).eq(datasetId))
          .execute();
    }
  }

  @Test
  void 미바인딩_데이터셋은_empty() {
    assertThat(bindingRepository.findOntologyIdByDataset(999999L)).isEmpty();
  }

  @Test
  void V77_dataset_ontology_행을_저장하고_읽을수있다() {
    // 테이블 존재 + 컬럼 계약을 실제 삽입/조회 왕복으로 검증한다(항상-참 count 대신 의미 있는 단언).
    long datasetId = 998877L;
    dsl.insertInto(table(name("dataset_ontology")))
        .set(field(name("dataset_id"), Long.class), datasetId)
        .set(field(name("ontology_id"), Long.class), 1L)
        .set(field(name("bound_by"), Long.class), 42L)
        .execute();
    try {
      Long readOntologyId =
          dsl.select(field(name("ontology_id"), Long.class))
              .from(table(name("dataset_ontology")))
              .where(field(name("dataset_id"), Long.class).eq(datasetId))
              .fetchOne(0, Long.class);
      assertThat(readOntologyId).isEqualTo(1L);
    } finally {
      dsl.deleteFrom(table(name("dataset_ontology")))
          .where(field(name("dataset_id"), Long.class).eq(datasetId))
          .execute();
    }
  }

  @Test
  void 신규_온톨로지_INSERT시_id가_자동발급된다() {
    // id를 지정하지 않고 INSERT하면 IDENTITY(START 2)로 2 이상이 발급되어야 한다.
    Long newId =
        dsl.insertInto(table(name("ontology")))
            .set(field(name("domain"), String.class), "V77_IDENTITY_PROBE")
            .set(field(name("schema_version"), Integer.class), 1)
            .returning(field(name("id"), Long.class))
            .fetchOne()
            .get(field(name("id"), Long.class));
    try {
      assertThat(newId).isGreaterThanOrEqualTo(2L);
    } finally {
      // 정리: 이 테스트가 만든 온톨로지 삭제(롤백 없음). 단언 실패 시에도 반드시 삭제되어야
      // 이후 재실행 시 domain UNIQUE 제약 위반(고아 행)이 발생하지 않는다.
      dsl.deleteFrom(table(name("ontology")))
          .where(field(name("id"), Long.class).eq(newId))
          .execute();
    }
  }
}
