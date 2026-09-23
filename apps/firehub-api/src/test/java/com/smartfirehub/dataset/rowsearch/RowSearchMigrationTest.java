package com.smartfirehub.dataset.rowsearch;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.support.IntegrationTestBase;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** V130: 검색 설정 컬럼과 색인 상태 테이블(+RLS)이 존재하는지 확인한다. */
class RowSearchMigrationTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;

  @Test
  void datasetColumn_hasIsSearchable_defaultFalse() {
    var row =
        dsl.fetchOne(
            "SELECT data_type, column_default, is_nullable FROM information_schema.columns"
                + " WHERE table_schema='public' AND table_name='dataset_column' AND column_name='is_searchable'");
    assertThat(row).isNotNull();
    assertThat(row.get("data_type", String.class)).isEqualTo("boolean");
    assertThat(row.get("column_default", String.class)).isEqualTo("false");
    assertThat(row.get("is_nullable", String.class)).isEqualTo("NO");
  }

  @Test
  void datasetSearchIndex_existsWithRlsEnabled() {
    Boolean rls =
        dsl.fetchOne(
                "SELECT relrowsecurity FROM pg_class WHERE oid = 'public.dataset_search_index'::regclass")
            .get(0, Boolean.class);
    assertThat(rls).isTrue();
    Integer policies =
        dsl.fetchOne(
                "SELECT count(*) FROM pg_policies WHERE tablename = 'dataset_search_index'"
                    + " AND policyname = 'dataset_search_index_tenant_isolation'")
            .get(0, Integer.class);
    assertThat(policies).isEqualTo(1);
  }
}
