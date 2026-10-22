package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.DataStoreResolver;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.exception.UnsafeSqlException;
import com.smartfirehub.pipeline.service.validator.SqlValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 테넌트 백엔드별 "파이프라인 SQL 의 데이터 스키마" 판정을 고정한다(WD-77, 스펙 §5.0).
 *
 * <p>PG 테넌트는 기존 {@link DataSchema} 와 바이트 단위로 같은 값을, Tibero 테넌트는 실제 스키마 {@code GENIA_T{id}} 와 대문자 물리
 * 이름을 쓴다.
 */
class PipelineDataSchemaTest {

  private final DataStoreResolver resolver = mock(DataStoreResolver.class);
  private final SqlValidator pgValidator = new SqlValidator();
  private final PipelineDataSchema schema = new PipelineDataSchema(resolver, pgValidator);

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  private void tenant(long id, DataStoreBackend backend) {
    TenantContext.set(id);
    when(resolver.currentBackend()).thenReturn(backend);
  }

  @Test
  void tiberoTenant_usesGeniaSchemaAndUppercasePhysicalNames() {
    tenant(5L, DataStoreBackend.TIBERO);

    assertThat(schema.isTibero()).isTrue();
    assertThat(schema.current()).isEqualTo("GENIA_T5");
    assertThat(schema.qualify("orders")).isEqualTo("\"GENIA_T5\".\"ORDERS\"");
  }

  @Test
  void tiberoTenant_validatorAllowsOnlyTenantSchema() {
    tenant(5L, DataStoreBackend.TIBERO);

    SqlValidator v = schema.validator();
    v.validate("SELECT * FROM genia_t5.orders");
    v.validate("SELECT * FROM " + schema.qualify("orders"));
    assertThatThrownBy(() -> v.validate("SELECT * FROM data_t5.orders"))
        .isInstanceOf(UnsafeSqlException.class);
    assertThatThrownBy(() -> v.validate("SELECT * FROM genia_t6.orders"))
        .isInstanceOf(UnsafeSqlException.class);
  }

  @Test
  void pgTenant_matchesDataSchemaExactly() {
    tenant(5L, DataStoreBackend.POSTGRESQL);

    assertThat(schema.isTibero()).isFalse();
    assertThat(schema.current()).isEqualTo(DataSchema.current());
    assertThat(schema.qualify("orders")).isEqualTo(DataSchema.qualify("orders"));
    assertThat(schema.qualify("we\"ird")).isEqualTo(DataSchema.qualify("we\"ird"));
    assertThat(schema.validator()).isSameAs(pgValidator);
  }

  @Test
  void legacyPgTenant_matchesDataSchemaExactly() {
    tenant(1L, DataStoreBackend.POSTGRESQL);

    assertThat(schema.current()).isEqualTo(DataSchema.current());
    assertThat(schema.qualify("orders")).isEqualTo(DataSchema.qualify("orders"));
  }
}
