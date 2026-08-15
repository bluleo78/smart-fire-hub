package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** V93 이 pipeline 도메인 11개 테이블에 NOT NULL + GUC DEFAULT 로 tenant_id 를 심었는지 고정한다. */
class PipelineDomainColumnTest extends IntegrationTestBase {

  private static final List<String> TABLES =
      List.of(
          "pipeline",
          "pipeline_step",
          "pipeline_step_input",
          "pipeline_step_dependency",
          "pipeline_execution",
          "pipeline_step_execution",
          "pipeline_trigger",
          "trigger_event",
          "api_connection",
          "async_job",
          "uploaded_files");

  @Autowired private DSLContext dsl;

  /** V93 이 11테이블 전부에 tenant_id 를 NOT NULL + GUC DEFAULT 로 추가했는지. */
  @Test
  void allPipelineDomainTablesHaveTenantColumn() {
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

  /** 접어야 할 유니크 2건이 실제로 tenant_id 를 선두로 갖는지. */
  @Test
  void unusableGlobalUniquesAreFolded() {
    assertThat(indexDef("pipeline", "idx_pipeline_name")).contains("tenant_id").contains("name");

    String asyncJob = indexDef("async_job", "idx_async_job_active_unique");
    assertThat(asyncJob).contains("tenant_id");
    // 부분 인덱스의 WHERE 절이 사라지면 종료된 잡까지 유니크가 걸려 재실행이 막힌다.
    assertThat(asyncJob).containsIgnoringCase("where");
  }

  /** 부모 스코프라 접지 않기로 한 유니크는 그대로여야 한다 — 이 결정을 고정한다. */
  @Test
  void parentScopedUniquesAreLeftAlone() {
    assertThat(indexDef("pipeline_step", "pipeline_step_pipeline_id_name_key"))
        .contains("pipeline_id")
        .contains("name")
        .doesNotContain("tenant_id");
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
