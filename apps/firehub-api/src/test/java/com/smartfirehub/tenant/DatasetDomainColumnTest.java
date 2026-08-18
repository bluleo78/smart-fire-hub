package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** V87 이 dataset 도메인 7개 테이블에 NOT NULL + GUC DEFAULT 로 tenant_id 를 심었는지 고정한다. */
class DatasetDomainColumnTest extends IntegrationTestBase {

  private static final List<String> TABLES =
      List.of(
          "dataset",
          "dataset_category",
          "dataset_column",
          "dataset_tag",
          "dataset_favorite",
          "dataset_embedding",
          "file_dataset_config");

  @Autowired private DSLContext dsl;

  @Test
  void everyTableHasNotNullTenantIdWithGucDefault() {
    for (String tableName : TABLES) {
      var row =
          dsl.fetchOne(
              "select is_nullable, column_default from information_schema.columns"
                  + " where table_schema='public' and table_name=? and column_name='tenant_id'",
              tableName);
      assertThat(row).as("%s.tenant_id 컬럼이 없다", tableName).isNotNull();
      assertThat(row.get("is_nullable", String.class))
          .as("%s.tenant_id nullable", tableName)
          .isEqualTo("NO");
      assertThat(row.get("column_default", String.class))
          .as("%s.tenant_id DEFAULT 가 GUC 를 읽어야 한다", tableName)
          .contains("app.tenant_id");
    }
  }

  @Test
  void datasetNameAndTableNameUniquesAreBothScopedToTenant() {
    assertThat(indexDef("dataset", "idx_dataset_name")).contains("tenant_id").contains("name");

    // P3-b2 T5(V112)가 이 단언을 뒤집었다 — 예전 이름은
    // datasetNameUniqueIsScopedToTenantButTableNameIsNot 이었고 "table_name 은 의도적으로
    // 전역 유니크로 남는다"를 고정했다. 그 전제(DataSchema.current() 가 상수 "data")는
    // P3-b2 T1(커밋 266c002b)이 테넌트별 물리 스키마로 갈라놓으면서 사라졌다 — 물리 충돌이
    // 구조적으로 불가능해졌으므로, table_name 유니크도 이제 dataset_name 과 같은 형태로
    // (tenant_id, table_name) 로 접는 것이 맞다(V109 가 먼저 이 접기를 했다가 순서가 틀려
    // V110 으로 되돌려졌고, V112 가 스키마 분리 이후에 다시 접었다 — task-1-report.md 와
    // V109/V110/V112 마이그레이션 헤더 참조). 크로스 테넌트 동명 table_name 허용과 데이터
    // 손실 부재는 DataTableServiceTenantUniqueTest 가 실제 DDL 경로로 고정한다.
    assertThat(indexDef("dataset", "idx_dataset_table_name")).contains("tenant_id");
  }

  @Test
  void datasetCategoryNameUniqueIsScopedToTenant() {
    assertThat(indexDef("dataset_category", "dataset_category_tenant_name_key"))
        .as("카테고리 이름 유니크가 테넌트 스코프여야 한다")
        .contains("tenant_id");
  }

  /** 인덱스 정의를 읽는다. 인덱스가 없으면 단언이 아니라 명확한 실패 메시지를 내도록 한다. */
  private String indexDef(String tableName, String indexName) {
    var row =
        dsl.fetchOne(
            "select indexdef from pg_indexes where schemaname='public'"
                + " and tablename=? and indexname=?",
            tableName,
            indexName);
    assertThat(row).as("인덱스 %s.%s 가 존재하지 않는다", tableName, indexName).isNotNull();
    return row.get("indexdef", String.class);
  }
}
