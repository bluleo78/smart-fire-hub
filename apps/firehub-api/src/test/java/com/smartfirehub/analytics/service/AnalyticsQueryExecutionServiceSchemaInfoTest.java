package com.smartfirehub.analytics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.smartfirehub.analytics.dto.SchemaInfoResponse;
import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.securitylevel.sql.TiberoQueryService;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * WD-76 — {@code getSchemaInfo} 의 방언·스키마 필드와 Tibero 테넌트 메타데이터 기반 응답.
 *
 * <p>Tibero 테이블은 PG 에 없으므로 응답은 {@code dataset}/{@code dataset_column} 메타데이터에서 만든다. 테넌트 백엔드 판정은
 * {@link TiberoQueryService} 목으로 갈라 세우고, 데이터셋 메타 행은 PG 테스트 DB 에 실제로 만든다(PG 물리 테이블이 함께 생겨도 Tibero
 * 경로는 {@code information_schema} 를 보지 않으므로 결과에 영향이 없다). 가시성 조건({@code visibilitySql})은 보안 요건이라
 * Tibero 경로에서도 걸려야 한다.
 */
class AnalyticsQueryExecutionServiceSchemaInfoTest extends IntegrationTestBase {

  @Autowired private AnalyticsQueryExecutionService executionService;
  @Autowired private DatasetService datasetService;
  @Autowired private DSLContext dsl;

  @MockitoBean private TiberoQueryService tiberoQueryService;

  private Long createDataset(Long userId, String name, String table, String... cols) {
    List<DatasetColumnRequest> columns =
        java.util.Arrays.stream(cols)
            .map(
                c ->
                    new DatasetColumnRequest(
                        c, c.toUpperCase() + " 표시", "TEXT", null, true, false, null))
            .toList();
    return datasetService
        .createDataset(
            new CreateDatasetRequest(name, table, null, null, "TABLE", "SOURCE", columns, null),
            userId)
        .id();
  }

  private void cleanup(Long userId, Long... datasetIds) {
    inTenantFixture(
        () -> {
          for (Long id : datasetIds) {
            datasetService.deleteDataset(id);
          }
          dsl.deleteFrom(DSL.table(DSL.name("audit_log")))
              .where(DSL.field(DSL.name("audit_log", "user_id"), Long.class).eq(userId))
              .execute();
          dsl.deleteFrom(DSL.table(DSL.name("user")))
              .where(DSL.field(DSL.name("user", "id"), Long.class).eq(userId))
              .execute();
          return null;
        });
  }

  @Test
  void tibero_visibleDatasetOnly_metadataColumnsInOrder_withDialectAndSchema() {
    when(tiberoQueryService.isTibero()).thenReturn(true);
    when(tiberoQueryService.schemaName()).thenReturn("GENIA_T5");
    Long userId = inTenantFixture(this::createTestUser);
    Long shown =
        inTenantFixture(() -> createDataset(userId, "Tib 보임", "tib_shown", "zeta", "alpha"));
    Long hidden = inTenantFixture(() -> createDataset(userId, "Tib 숨김", "tib_hidden", "x"));
    // 문서형(물리 테이블 없음) 데이터셋 — 가시성을 통과해도 Tibero 응답에 나오면 안 된다(PG 경로와 동일).
    Long doc = inTenantFixture(() -> createDataset(userId, "Tib 문서", "tib_doc", "y"));
    inTenantFixture(
        () -> dsl.execute("UPDATE dataset SET storage_type = 'DOCUMENT' WHERE id = " + doc));
    try {
      SchemaInfoResponse res =
          executionService.getSchemaInfo(null, "d.id IN (" + shown + "," + doc + ")");

      assertThat(res.dialect()).isEqualTo("TIBERO");
      assertThat(res.schema()).isEqualTo("genia_t5");
      assertThat(res.tables()).hasSize(1);
      var t = res.tables().get(0);
      assertThat(t.tableName()).isEqualTo("tib_shown");
      assertThat(t.datasetId()).isEqualTo(shown);
      assertThat(t.datasetName()).isEqualTo("Tib 보임");
      // 생성 순서(column_order)대로이며 알파벳 순이 아니다.
      assertThat(t.columns())
          .extracting(SchemaInfoResponse.ColumnInfo::columnName)
          .containsExactly("zeta", "alpha");
      assertThat(t.columns())
          .extracting(SchemaInfoResponse.ColumnInfo::displayName)
          .containsExactly("ZETA 표시", "ALPHA 표시");
      assertThat(t.columns().get(0).dataType()).isEqualTo("TEXT");
    } finally {
      cleanup(userId, shown, hidden, doc);
    }
  }

  @Test
  void tibero_datasetIdsFilter_andEmptyList() {
    when(tiberoQueryService.isTibero()).thenReturn(true);
    when(tiberoQueryService.schemaName()).thenReturn("GENIA_T5");
    Long userId = inTenantFixture(this::createTestUser);
    Long a = inTenantFixture(() -> createDataset(userId, "Tib A", "tib_a", "c1"));
    Long b = inTenantFixture(() -> createDataset(userId, "Tib B", "tib_b", "c2"));
    try {
      SchemaInfoResponse only = executionService.getSchemaInfo(List.of(b), "TRUE");
      assertThat(only.tables())
          .extracting(SchemaInfoResponse.TableInfo::tableName)
          .containsExactly("tib_b");

      SchemaInfoResponse empty = executionService.getSchemaInfo(List.of(), "TRUE");
      assertThat(empty.tables()).isEmpty();
      assertThat(empty.dialect()).isEqualTo("TIBERO");
      assertThat(empty.schema()).isEqualTo("genia_t5");
    } finally {
      cleanup(userId, a, b);
    }
  }

  @Test
  void postgres_keepsExistingResult_plusDialectAndSchema() {
    when(tiberoQueryService.isTibero()).thenReturn(false);
    Long userId = inTenantFixture(this::createTestUser);
    Long id = inTenantFixture(() -> createDataset(userId, "Pg DS", "pg_schema_info", "col_a"));
    try {
      SchemaInfoResponse res = executionService.getSchemaInfo(List.of(id), "TRUE");
      assertThat(res.dialect()).isEqualTo("POSTGRESQL");
      assertThat(res.schema()).isEqualTo(com.smartfirehub.global.tenant.DataSchema.current());
      assertThat(res.tables())
          .extracting(SchemaInfoResponse.TableInfo::tableName)
          .containsExactly("pg_schema_info");

      SchemaInfoResponse empty = executionService.getSchemaInfo(List.of(), "TRUE");
      assertThat(empty.tables()).isEmpty();
      assertThat(empty.dialect()).isEqualTo("POSTGRESQL");
      assertThat(empty.schema()).isEqualTo(res.schema());
    } finally {
      cleanup(userId, id);
    }
  }

  /** 테스트마다 고유한 사용자를 만든다(커밋되는 픽스처라 username 충돌을 피한다). */
  private Long createTestUser() {
    String u = "schemainfo" + System.nanoTime();
    return dsl.insertInto(DSL.table(DSL.name("user")))
        .set(DSL.field(DSL.name("user", "username"), String.class), u)
        .set(DSL.field(DSL.name("user", "password"), String.class), "password")
        .set(DSL.field(DSL.name("user", "name"), String.class), "Schema Info Test User")
        .set(DSL.field(DSL.name("user", "email"), String.class), u + "@example.com")
        .returning(DSL.field(DSL.name("user", "id"), Long.class))
        .fetchOne()
        .get(DSL.field(DSL.name("user", "id"), Long.class));
  }
}
