package com.smartfirehub.dataset.rowsearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** PgRowSearchIndex 의 DDL·쓰기 경로를 실제 DB(테넌트 1 = data 스키마)에서 검증한다. */
class PgRowSearchIndexWriteTest extends IntegrationTestBase {

  // 공유 test DB 에서 다른 테스트와 겹치지 않는 가짜 데이터셋 id(색인 테이블 이름에만 쓰인다)
  private static final long DS = 990_001L;
  private static final String SRC = "rs_write_src";

  @Autowired private PgRowSearchIndex index;
  @Autowired private DataTableService dataTableService;
  @Autowired private DSLContext dsl;

  private final IndexRef ref = new IndexRef(DEFAULT_TEST_TENANT_ID, DS, SRC);

  static float[] vec(float first) {
    float[] v = new float[1024];
    v[0] = first;
    v[1] = 1f;
    return v;
  }

  @BeforeEach
  void setUp() {
    // 이전 실행의 tearDown 이 실패해 남은 원본·색인 테이블을 먼저 지운다(연쇄 실패 방지).
    RowSearchTestSupport.cleanup(dsl, dataTableService, SRC);
    RowSearchTestSupport.dropIndexTables(dsl, DS);
    inTenantFixture(
        () -> {
          dataTableService.createTable(
              SRC, List.of(new DatasetColumnRequest("content", "내용", "TEXT", null, true, false, null)));
          dsl.execute("INSERT INTO " + DataSchema.qualify(SRC) + " (content) VALUES ('a'), ('b'), ('c')");
        });
  }

  @AfterEach
  void tearDown() {
    inTenantFixture(
        () -> {
          index.drop(ref);
          dataTableService.dropTable(SRC);
        });
  }

  @Test
  void recreatedIndexTable_isNotAccessibleToPipelineExecutorRole() {
    // 스키마 기본 권한(ALTER DEFAULT PRIVILEGES)이 파이프라인 실행 롤에 DML 을 주므로, 색인 테이블은 만들 때 회수해야
    // 사용자 파이프라인 SQL 이 색인을 읽거나 오염시키지 못한다.
    String executor = com.smartfirehub.global.tenant.TenantPipelineRole.roleName(DEFAULT_TEST_TENANT_ID);
    boolean roleExists =
        dsl.fetchExists(dsl.selectOne().from("pg_roles").where(org.jooq.impl.DSL.field("rolname").eq(executor)));
    assertThat(roleExists).as("테스트 DB 에 실행 롤이 있어야 이 단언이 의미가 있다").isTrue();

    index.recreate(ref, 1024);
    index.rebuildReusing(ref, 1024); // 새 색인 + _prev(옛 색인 개명) 둘 다 확인

    for (String t : List.of(ref.indexTable(), ref.prevTable())) {
      String q = DataSchema.qualify(t);
      for (String priv : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
        Boolean has =
            dsl.fetchOne("SELECT has_table_privilege(?, ?, ?)", executor, q, priv).get(0, Boolean.class);
        assertThat(has).as(t + " " + priv).isFalse();
      }
    }
  }

  @Test
  void recreate_upsert_existingHashes_count() {
    index.recreate(ref, 1024);
    index.upsert(
        ref,
        List.of(
            new IndexedRow(1, "내용: a", "h1", vec(0.1f), "fake"),
            new IndexedRow(2, "내용: b", "h2", vec(0.2f), "fake")));
    // 같은 row_id 재upsert 는 갱신(중복 아님)
    index.upsert(ref, List.of(new IndexedRow(2, "내용: b2", "h2b", vec(0.3f), "fake")));

    assertThat(index.count(ref)).isEqualTo(2);
    assertThat(index.existingHashes(ref, List.of(1L, 2L, 3L)))
        .containsEntry(1L, "h1")
        .containsEntry(2L, "h2b")
        .doesNotContainKey(3L);
    String dimType =
        inTenantFixture(
            () ->
                dsl.fetchOne(
                        "SELECT format_type(atttypid, atttypmod) FROM pg_attribute WHERE attrelid = ?::regclass AND attname='embedding'",
                        DataSchema.qualify(ref.indexTable()))
                    .get(0, String.class));
    assertThat(dimType).isEqualTo("vector(1024)");
  }

  @Test
  void recreate_rejectsRefOfOtherTenant_andCreatesNothing() {
    // 현재 컨텍스트(테넌트 1)와 다른 테넌트의 참조 — 엉뚱한 스키마의 색인을 건드리지 않도록 거부해야 한다.
    IndexRef foreign = new IndexRef(987_654L, DS, SRC);

    assertThatThrownBy(() -> index.recreate(foreign, 1024))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("987654");

    Boolean exists =
        inTenantFixture(
            () ->
                dsl.fetchOne(
                        "SELECT to_regclass(?) IS NOT NULL", DataSchema.qualify(foreign.indexTable()))
                    .get(0, Boolean.class));
    assertThat(exists).isFalse();
  }

  @Test
  void deleteMissing_removesRowsAbsentFromSource() {
    index.recreate(ref, 1024);
    index.upsert(
        ref,
        List.of(
            new IndexedRow(1, "a", "h1", vec(0.1f), "fake"),
            new IndexedRow(999, "ghost", "hx", vec(0.1f), "fake")));

    int removed = index.deleteMissing(ref);

    assertThat(removed).isEqualTo(1);
    assertThat(index.existingHashes(ref, List.of(1L, 999L))).containsOnlyKeys(1L);
  }

  @Test
  void rebuildReusing_keepsPrev_andCopiesByHashAndModel() {
    index.recreate(ref, 1024);
    index.upsert(ref, List.of(new IndexedRow(1, "a", "same-hash", vec(0.7f), "fake")));

    index.rebuildReusing(ref, 1024);

    assertThat(index.hasPrev(ref)).isTrue();
    assertThat(index.count(ref)).isZero();
    // 새 row_id=3 이 같은 내용(해시)이면 벡터를 복사한다. 모델이 다르면 복사하지 않는다.
    assertThat(index.upsertReusing(ref, 3, "a", "same-hash", "fake")).isTrue();
    assertThat(index.upsertReusing(ref, 2, "a", "same-hash", "other-model")).isFalse();
    assertThat(index.upsertReusing(ref, 2, "b", "unknown-hash", "fake")).isFalse();
    assertThat(index.existingHashes(ref, List.of(3L))).containsEntry(3L, "same-hash");

    index.dropPrev(ref);
    assertThat(index.hasPrev(ref)).isFalse();
  }
}
