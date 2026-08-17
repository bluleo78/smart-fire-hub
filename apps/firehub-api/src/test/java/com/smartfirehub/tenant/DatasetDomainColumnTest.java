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
  void datasetNameUniqueIsScopedToTenantButTableNameIsNot() {
    assertThat(indexDef("dataset", "idx_dataset_name")).contains("tenant_id").contains("name");

    // table_name 은 공유 data 스키마의 실제 테이블명이라 **의도적으로** 전역 유니크로 남는다.
    // DataSchema.current() 가 상수 "data" 를 돌려주는 동안(P3-b 에서야 테넌트별로 갈라진다)
    // 두 테넌트의 같은 table_name 은 같은 물리 테이블 하나로 해석되고, data 스키마에는 RLS 가
    // 없다. 접으면 DataTableService.createTable 의 DROP TABLE IF EXISTS 가 앞선 테넌트의 데이터를
    // 조용히 지운다 — V109 가 앞당겨 접었다가 V110 으로 되돌린 이유다. 유니크 접기는 스키마
    // 분리와 **같은 밴드**에서만 해야 한다.
    assertThat(indexDef("dataset", "idx_dataset_table_name")).doesNotContain("tenant_id");
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
