package com.smartfirehub.dataset.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.DataStoreResolver;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.service.PipelineDataSchema;
import com.smartfirehub.pipeline.service.PipelineService;
import com.smartfirehub.pipeline.service.validator.SqlValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 데이터셋 사용처 SQL 대조가 테넌트 방언(PG/Tibero)으로 판정되는지 고정한다(WD-206).
 *
 * <p>Tibero 테넌트는 {@code genia_t{id}.테이블} 참조를 대문자 접기 규칙으로 찾아야 하고, PG 테넌트의 기존 동작은 그대로여야 한다.
 */
class DatasetSqlReferenceMatcherTest {

  private final DataStoreResolver resolver = mock(DataStoreResolver.class);
  private final PipelineDataSchema pds =
      new PipelineDataSchema(resolver, SqlValidator.forAdhocDataSchemaQueries());

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  /** 테넌트 컨텍스트와 백엔드를 정한 뒤 해당 테이블의 대조 범위를 만든다. */
  private DatasetSqlReferenceMatcher.Scope scope(DataStoreBackend backend, String table) {
    TenantContext.set(5L);
    when(resolver.currentBackend()).thenReturn(backend);
    return DatasetSqlReferenceMatcher.scope(pds, table);
  }

  private boolean tibero(String sql, String table) {
    return DatasetSqlReferenceMatcher.references(sql, scope(DataStoreBackend.TIBERO, table));
  }

  @Test
  void tibero_qualifiedLowercase_matches() {
    assertThat(tibero("SELECT region FROM genia_t5.orders", "orders")).isTrue();
  }

  @Test
  void tibero_mixedCase_matches() {
    String sql = "SELECT * FROM GENIA_T5.Orders o JOIN genia_t5.customers c ON o.cid = c.id";
    assertThat(tibero(sql, "orders")).isTrue();
    assertThat(tibero(sql, "customers")).isTrue();
  }

  @Test
  void tibero_quotedUpper_matches() {
    assertThat(tibero("SELECT * FROM \"GENIA_T5\".\"ORDERS\"", "orders")).isTrue();
  }

  @Test
  void tibero_quotedLowercase_doesNotMatch() {
    assertThat(tibero("SELECT * FROM genia_t5.\"orders\"", "orders")).isFalse();
  }

  @Test
  void tibero_otherTenant_doesNotMatch() {
    assertThat(tibero("SELECT * FROM genia_t6.orders", "orders")).isFalse();
  }

  @Test
  void tibero_similarName_literal_comment_cte_doNotMatch() {
    assertThat(tibero("SELECT * FROM genia_t5.orders_2", "orders")).isFalse();
    assertThat(tibero("SELECT 'orders' FROM dual", "orders")).isFalse();
    assertThat(tibero("SELECT 1 FROM dual -- genia_t5.orders", "orders")).isFalse();
    assertThat(tibero("WITH orders AS (SELECT 1 FROM dual) SELECT * FROM orders", "orders"))
        .isFalse();
  }

  @Test
  void tibero_write_matches() {
    assertThat(tibero("INSERT INTO genia_t5.orders (id) SELECT id FROM genia_t5.staging", "orders"))
        .isTrue();
  }

  @Test
  void tibero_pipelineSubstitutedSql_matches() {
    String sql =
        PipelineService.substituteStepReferencesForValidation(
            "SELECT * FROM genia_t5.orders WHERE _updated_at > {{last_run_at}}");
    assertThat(tibero(sql, "orders")).isTrue();
  }

  @Test
  void pg_scope_keepsCurrentBehaviour() {
    DatasetSqlReferenceMatcher.Scope s = scope(DataStoreBackend.POSTGRESQL, "orders");
    assertThat(DatasetSqlReferenceMatcher.references("SELECT * FROM data_t5.orders", s)).isTrue();
    assertThat(DatasetSqlReferenceMatcher.references("SELECT * FROM orders", s)).isTrue();
    assertThat(DatasetSqlReferenceMatcher.references("SELECT * FROM genia_t5.orders", s)).isFalse();
  }

  @Test
  void unparsable_isFalse() {
    assertThat(tibero("SELEC broken", "orders")).isFalse();
    DatasetSqlReferenceMatcher.Scope s = scope(DataStoreBackend.POSTGRESQL, "orders");
    assertThat(DatasetSqlReferenceMatcher.references("SELEC broken", s)).isFalse();
  }
}
