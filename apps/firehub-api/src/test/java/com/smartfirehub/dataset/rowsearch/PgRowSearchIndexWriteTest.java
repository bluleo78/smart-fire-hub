package com.smartfirehub.dataset.rowsearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.global.datastore.DataStoreDb;
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

  /** 데이터 저장소(data 스키마) 테이블 조작·검사용 — 분리 모드에서는 플랫폼 DB 와 다른 DB 다(WD-494). */
  @Autowired @DataStoreDb private DSLContext dataDsl;

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
    RowSearchTestSupport.cleanup(dsl, dataDsl, dataTableService, SRC);
    RowSearchTestSupport.dropIndexTables(dataDsl, DS);
    inTenantFixture(
        () -> {
          dataTableService.createTable(
              SRC,
              List.of(new DatasetColumnRequest("content", "내용", "TEXT", null, true, false, null)));
          dataDsl.execute(
              "INSERT INTO " + DataSchema.qualify(SRC) + " (content) VALUES ('a'), ('b'), ('c')");
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
    String executor =
        com.smartfirehub.global.tenant.TenantPipelineRole.roleName(DEFAULT_TEST_TENANT_ID);
    boolean roleExists =
        dataDsl.fetchExists(
            dataDsl
                .selectOne()
                .from("pg_roles")
                .where(org.jooq.impl.DSL.field("rolname").eq(executor)));
    assertThat(roleExists).as("테스트 DB 에 실행 롤이 있어야 이 단언이 의미가 있다").isTrue();

    index.recreate(ref, 1024);
    index.startGeneration(ref, 1024); // 새 색인 + _prev(옛 색인 개명) 둘 다 확인

    for (String t : List.of(ref.indexTable(), ref.prevTable())) {
      String q = DataSchema.qualify(t);
      for (String priv : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
        Boolean has =
            dataDsl
                .fetchOne("SELECT has_table_privilege(?, ?, ?)", executor, q, priv)
                .get(0, Boolean.class);
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
                dataDsl
                    .fetchOne(
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
                dataDsl
                    .fetchOne(
                        "SELECT to_regclass(?) IS NOT NULL",
                        DataSchema.qualify(foreign.indexTable()))
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
  void startGeneration_keepsPrev_andCopiesByHashAndModel() {
    index.recreate(ref, 1024);
    index.upsert(ref, List.of(new IndexedRow(1, "a", "same-hash", vec(0.7f), "fake")));

    index.startGeneration(ref, 1024);

    assertThat(index.hasPreviousGeneration(ref)).isTrue();
    assertThat(index.count(ref)).isZero();
    // 새 row_id=3 이 같은 내용(해시)이면 벡터를 복사한다. 모델이 다르면 복사하지 않는다.
    // 배치째(WD-743): 같은 해시의 두 행(3·4)은 모두 복사되고, 모르는 해시(2)는 빠진다 — 한 문장으로 처리한다.
    assertThat(
            index.upsertReusing(
                ref,
                List.of(
                    new ReuseCandidate(3, "a", "same-hash"),
                    new ReuseCandidate(4, "a", "same-hash"),
                    new ReuseCandidate(2, "b", "unknown-hash")),
                "fake"))
        .containsExactlyInAnyOrder(3L, 4L);
    assertThat(
            index.upsertReusing(
                ref, List.of(new ReuseCandidate(5, "a", "same-hash")), "other-model"))
        .isEmpty();
    assertThat(index.upsertReusing(ref, List.of(), "fake")).isEmpty();
    assertThat(index.existingHashes(ref, List.of(2L, 3L, 4L, 5L)))
        .containsOnlyKeys(3L, 4L)
        .containsEntry(3L, "same-hash");

    index.dropPreviousGenerations(ref);
    assertThat(index.hasPreviousGeneration(ref)).isFalse();
  }

  /** 색인 테이블의 trgm 색인 접근 방법(gist·gin) 목록. */
  private List<String> trgmIndexMethods() {
    return dataDsl
        .fetch(
            "SELECT am.amname FROM pg_index i JOIN pg_class ic ON ic.oid = i.indexrelid"
                + " JOIN pg_am am ON am.oid = ic.relam JOIN pg_opclass oc ON oc.oid = i.indclass[0]"
                + " WHERE i.indrelid = to_regclass(?) AND oc.opcname IN ('gist_trgm_ops', 'gin_trgm_ops')",
            DataSchema.qualify(ref.indexTable()))
        .map(r -> r.get(0, String.class));
  }

  /** WD-725 — 새 색인 테이블의 키워드 색인은 GiST 라 거리 연산자(<->>) 정렬이 색인 스캔(Order By)으로 풀린다. */
  @Test
  void recreate_makesGistKeywordIndex_usableForDistanceOrder() {
    index.recreate(ref, 1024);
    assertThat(trgmIndexMethods()).containsExactly("gist");

    String q = DataSchema.qualify(ref.indexTable());
    String plan =
        dataDsl.transactionResult(
            cfg -> {
              DSLContext tx = org.jooq.impl.DSL.using(cfg);
              // 행이 적어 비용상 seq·bitmap scan 을 고르므로 끄고, 색인이 거리 정렬(Order By)을 맡을 수 있는지만 본다.
              tx.execute("SET LOCAL enable_seqscan = off");
              tx.execute("SET LOCAL enable_bitmapscan = off");
              return String.join(
                  "\n",
                  tx.fetch(
                          "EXPLAIN SELECT row_id FROM "
                              + q
                              + " s WHERE s.source_text %> ? ORDER BY s.source_text <->> ? LIMIT 5",
                          "배관",
                          "배관")
                      .map(r -> r.get(0, String.class)));
            });
    assertThat(plan).contains("Order By").contains("Index Scan");
  }

  /** WD-725 — 이 변경 전에 만든 GIN 색인 테이블은 동기화가 한 번 GiST 로 올리고 GIN 을 지운다. 키워드 검색 결과는 그대로다. */
  @Test
  void ensureKeywordIndex_upgradesLegacyGinTable() {
    index.recreate(ref, 1024);
    String q = DataSchema.qualify(ref.indexTable());
    // 옛 판처럼 GIN 키워드 색인만 남긴다.
    for (var r :
        dataDsl.fetch(
            "SELECT ic.relname FROM pg_index i JOIN pg_class ic ON ic.oid = i.indexrelid"
                + " JOIN pg_opclass oc ON oc.oid = i.indclass[0]"
                + " WHERE i.indrelid = to_regclass(?) AND oc.opcname = 'gist_trgm_ops'",
            q)) {
      dataDsl.execute("DROP INDEX " + DataSchema.current() + ".\"" + r.get(0, String.class) + "\"");
    }
    dataDsl.execute("CREATE INDEX ON " + q + " USING gin (source_text public.gin_trgm_ops)");
    assertThat(trgmIndexMethods()).containsExactly("gin");
    index.upsert(ref, List.of(new IndexedRow(1, "배관 누수 발생", "h1", vec(0.1f), "fake")));

    index.ensureKeywordIndex(ref);
    assertThat(trgmIndexMethods()).containsExactly("gist");
    // 두 번째 호출은 기억으로 건너뛴다(색인이 늘지 않는다).
    index.ensureKeywordIndex(ref);
    assertThat(trgmIndexMethods()).containsExactly("gist");
    assertThat(index.keyword(ref, "배관", RowQueryFilter.none(), 5))
        .extracting(RowHit::rowId)
        .containsExactly(1L);
  }
}
