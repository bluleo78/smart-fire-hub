package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * V101 이 만든 온톨로지·그래프 8테이블의 스키마 형태를 카탈로그로 고정한다.
 *
 * <p>왜 필요한가: (1) 유니크 인덱스는 RLS 와 무관하게 전역으로 적용되므로 접지 않으면 두 번째
 * 테넌트가 같은 domain·dataset_id·dedupe_key 로 쓸 때 "보이지도 않는 행"과 충돌한다. (2) tenant_id
 * DEFAULT 가 GUC 를 읽지 않으면 앱이 값을 안 넣는 지금 구조에서 INSERT 가 NOT NULL 위반으로 깨진다.
 * 두 회귀 모두 테넌트가 하나뿐인 동안에는 행위 테스트로 드러나지 않아서 카탈로그로 못박는다.
 *
 * <p>주의: 이 밴드는 RDB 온톨로지 스키마만 격리한다 — Neo4j 그래프 자체는 여전히 미격리다.
 */
class OntologyGraphSchemaTest extends IntegrationTestBase {

  /** P2-d 범위의 8테이블. 모두 표준 정책(테넌트 필수) 대상이라 nullable 인 tenant_id 는 없다. */
  private static final List<String> TABLES =
      List.of(
          "ontology",
          "ontology_entity_type",
          "ontology_entity_property",
          "ontology_relation",
          "dataset_ontology",
          "dataset_mapping",
          "dataset_graph_ingest",
          "graph_review_item");

  @Autowired private DSLContext dsl;

  private boolean columnIsNullable(String table, String column) {
    return "YES"
        .equals(
            dsl.fetchValue(
                "select is_nullable from information_schema.columns"
                    + " where table_schema='public' and table_name=? and column_name=?",
                table,
                column));
  }

  private String columnDefault(String table, String column) {
    return (String)
        dsl.fetchValue(
            "select column_default from information_schema.columns"
                + " where table_schema='public' and table_name=? and column_name=?",
            table,
            column);
  }

  @Test
  @DisplayName("8테이블의 tenant_id 가 NOT NULL 이고 DEFAULT 가 GUC 를 읽는다")
  void tenantColumnsAreNotNullWithGucDefault() {
    for (String table : TABLES) {
      assertThat(columnIsNullable(table, "tenant_id")).as("%s.tenant_id nullable", table).isFalse();
      // 앱은 tenant_id 를 직접 쓰지 않는다 — DEFAULT 가 세션 GUC 에서 받아야 INSERT 가 성립한다.
      assertThat(columnDefault(table, "tenant_id"))
          .as("%s.tenant_id default", table)
          .contains("current_setting")
          .contains("app.tenant_id");
    }
  }

  @Test
  @DisplayName("8테이블 모두 tenant(id) 로 가는 FK 가 있다")
  void tenantForeignKeysExist() {
    for (String table : TABLES) {
      Integer fkCount =
          (Integer)
              dsl.fetchValue(
                  "select count(*)::int from pg_constraint c"
                      + " where c.conrelid = ?::regclass and c.contype = 'f'"
                      + " and c.confrelid = 'tenant'::regclass"
                      + " and c.conkey = array[(select attnum from pg_attribute"
                      + "   where attrelid = c.conrelid and attname = 'tenant_id')]",
                  table);
      assertThat(fkCount).as("%s → tenant(id) FK", table).isEqualTo(1);
    }
  }

  @Test
  @DisplayName("접은 유니크 4개가 tenant_id 를 선두 컬럼으로 갖는다")
  void foldedUniquesLeadWithTenantId() {
    // (인덱스명, 소유 테이블) — 유니크는 RLS 를 우회해 전역 적용되므로 선두가 tenant_id 여야 한다.
    assertUniqueLeadsWithTenant("ontology_domain_unique", "ontology");
    assertUniqueLeadsWithTenant("dataset_ontology_dataset_id_key", "dataset_ontology");
    assertUniqueLeadsWithTenant("dataset_mapping_dataset_id_key", "dataset_mapping");
    assertUniqueLeadsWithTenant("uq_graph_review_item", "graph_review_item");
  }

  /** indkey[0](선두 컬럼)의 attnum 이 해당 테이블 tenant_id 의 attnum 과 같은지 확인한다. */
  private void assertUniqueLeadsWithTenant(String indexName, String table) {
    Boolean leads =
        (Boolean)
            dsl.fetchValue(
                "select i.indisunique and i.indkey[0] = ("
                    + "  select attnum from pg_attribute"
                    + "  where attrelid = i.indrelid and attname = 'tenant_id')"
                    + " from pg_index i join pg_class c on c.oid = i.indexrelid"
                    + " join pg_namespace n on n.oid = c.relnamespace"
                    + " where n.nspname = 'public' and c.relname = ?",
                indexName);
    assertThat(leads).as("%s on %s leads with tenant_id", indexName, table).isTrue();
  }

  @Test
  @DisplayName("V102 가 8테이블의 RLS 를 켰고 FORCE 는 꺼져 있다")
  void rlsEnabledWithoutForce() {
    // V101(컬럼)과 V102(정책)를 나눈 이유: 중간 커밋에서 온톨로지 UI·GraphRAG MCP 툴이 죽지 않게
    // 하기 위함이었다. V102 가 들어온 지금은 켜져 있어야 한다.
    //
    // relforcerowsecurity 는 반드시 false 여야 한다 — true 면 테이블 소유자에게까지 정책이 적용돼
    // V98 의 provision_tenant_defaults 와 V95 의 트리거 해석 함수(둘 다 SECURITY DEFINER)가 0행을
    // 받아 프로비저닝·외부 웹훅 트리거가 전멸한다. 실수로 켜지는 것을 카탈로그로 못박는다.
    for (String table : TABLES) {
      Boolean rls =
          (Boolean)
              dsl.fetchValue(
                  "select relrowsecurity from pg_class where oid = ?::regclass", table);
      assertThat(rls).as("%s RLS enabled", table).isTrue();

      Boolean forced =
          (Boolean)
              dsl.fetchValue(
                  "select relforcerowsecurity from pg_class where oid = ?::regclass", table);
      assertThat(forced).as("%s FORCE RLS must stay off", table).isFalse();
    }
  }

  @Test
  @DisplayName("8테이블 모두 표준 형태의 격리 정책을 하나씩 갖는다")
  void standardIsolationPoliciesExist() {
    // 형태 (b)(IS NOT DISTINCT FROM)는 GUC 가 비면 NULL-vs-NULL 이 참이 되어 fail-open 이다.
    // 이 8테이블은 tenant_id NOT NULL 이라 NULL 행이 없으므로 전부 표준 형태 (a) 여야 한다.
    for (String table : TABLES) {
      String qual =
          (String)
              dsl.fetchValue(
                  "select qual from pg_policies where schemaname='public' and tablename=?"
                      + " and policyname = ? ",
                  table,
                  table + "_tenant_isolation");
      assertThat(qual)
          .as("%s 격리 정책 USING", table)
          .isNotNull()
          .contains("app.tenant_id")
          .doesNotContain("IS NOT DISTINCT FROM");

      String withCheck =
          (String)
              dsl.fetchValue(
                  "select with_check from pg_policies where schemaname='public' and tablename=?"
                      + " and policyname = ?",
                  table,
                  table + "_tenant_isolation");
      assertThat(withCheck)
          .as("%s 격리 정책 WITH CHECK — 없으면 남의 테넌트에 행을 심을 수 있다", table)
          .isNotNull()
          .contains("app.tenant_id")
          .doesNotContain("IS NOT DISTINCT FROM");
    }
  }
}
