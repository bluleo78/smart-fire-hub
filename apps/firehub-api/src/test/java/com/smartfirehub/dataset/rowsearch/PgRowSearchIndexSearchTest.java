package com.smartfirehub.dataset.rowsearch;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 의미·키워드 검색과 필터 조인을 검증한다. 벡터는 축 방향으로 만들어 코사인 순위를 결정적으로 만든다. */
class PgRowSearchIndexSearchTest extends IntegrationTestBase {

  private static final long DS = 990_002L;
  private static final String SRC = "rs_search_src";

  @Autowired private PgRowSearchIndex index;
  @Autowired private DataTableService dataTableService;
  @Autowired private DSLContext dsl;

  private final IndexRef ref = new IndexRef(DEFAULT_TEST_TENANT_ID, DS, SRC);

  /** axis 방향 단위벡터 — 질의와 같은 축이면 코사인 1. */
  static float[] axis(int axis) {
    float[] v = new float[1024];
    v[axis] = 1f;
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
              SRC,
              List.of(
                  new DatasetColumnRequest("content", "내용", "TEXT", null, true, false, null),
                  new DatasetColumnRequest("status", "상태", "VARCHAR", 20, true, false, null),
                  new DatasetColumnRequest("cnt", "건수", "INTEGER", null, true, false, null)));
          dsl.execute(
              "INSERT INTO " + DataSchema.qualify(SRC)
                  + " (content, status, cnt) VALUES ('배관 누수 발생', '미처리', 1), ('소음 민원', '완료', 2), ('누수 재발', '완료', 3)");
        });
    index.recreate(ref, 1024);
    index.upsert(
        ref,
        List.of(
            new IndexedRow(1, "내용: 배관 누수 발생", "h1", axis(0), "fake"),
            new IndexedRow(2, "내용: 소음 민원", "h2", axis(1), "fake"),
            new IndexedRow(3, "내용: 누수 재발", "h3", axis(2), "fake")));
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
  void semantic_ranksByCosine() {
    List<RowHit> hits = index.semantic(ref, axis(1), CompiledFilter.none(), 2);
    assertThat(hits).extracting(RowHit::rowId).first().isEqualTo(2L);
    assertThat(hits.get(0).score()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
  }

  @Test
  void semantic_withFilter_joinsSourceAndStillFillsLimit() {
    var filter =
        RowFilterCompiler.compile(
            new RowFilter(List.of(new RowFilter.Condition("status", "eq", "완료"))),
            Map.of("content", "TEXT", "status", "VARCHAR"));

    List<RowHit> hits = index.semantic(ref, axis(0), filter, 5);

    // row 1 은 '미처리' 라 제외. 나머지 완료 2건이 모두 반환된다(iterative scan 으로 결과 부족 없음).
    assertThat(hits).extracting(RowHit::rowId).containsExactlyInAnyOrder(2L, 3L);
  }

  @Test
  void semantic_withIntegerInFilter_bindsTypedArray() {
    var filter =
        RowFilterCompiler.compile(
            new RowFilter(List.of(new RowFilter.Condition("cnt", "in", List.of(1, 3)))),
            Map.of("content", "TEXT", "status", "VARCHAR", "cnt", "INTEGER"));

    List<RowHit> hits = index.semantic(ref, axis(0), filter, 5);

    assertThat(hits).extracting(RowHit::rowId).containsExactlyInAnyOrder(1L, 3L);
  }

  /**
   * 정확 스캔 SQL 은 HNSW 인덱스로 정렬하지 못하고, 필터 조인은 여전히 원본 PK 인덱스를 쓴다. 행 3개라 플래너가
   * 원래도 순차 스캔을 고를 수 있으므로 enable_seqscan 을 꺼서 "인덱스를 쓸 수 있으면 쓰는" 상태로 비교한다.
   *
   * <p>뮤테이션: 정렬식의 {@code + 0} 감싸기를 지우면 exact 계획에도 embedding 인덱스가 나타나 빨개진다.
   */
  @Test
  void semanticSql_exactBypassesHnswButFilterJoinKeepsPk() {
    var filter =
        RowFilterCompiler.compile(
            new RowFilter(List.of(new RowFilter.Condition("status", "eq", "완료"))),
            Map.of("content", "TEXT", "status", "VARCHAR"));
    String vec = com.smartfirehub.dataset.search.VectorLiterals.toVectorLiteral(axis(0));

    // 필터가 없으면 정렬만 남아 두 경로의 차이가 그대로 드러난다(필터가 있으면 작은 표에선 HNSW 경로도 조인을 고른다).
    assertThat(explain(CompiledFilter.none(), false, vec)).contains("embedding_idx");
    assertThat(explain(CompiledFilter.none(), true, vec)).doesNotContain("embedding_idx");
    // 필터가 있어도 정확 스캔은 embedding 인덱스를 쓰지 않고, 조인은 원본 PK 인덱스를 그대로 쓴다.
    assertThat(explain(filter, true, vec)).doesNotContain("embedding_idx").contains("rs_search_src_pkey");
  }

  /** 행 수가 임계값 이하면 정확 스캔, 넘으면 HNSW. 통계가 없을 때(-1)와 ANALYZE 뒤 모두 같은 판정이어야 한다. */
  @Test
  void useExactScan_comparesRowCountWithThreshold() {
    assertThat(new PgRowSearchIndex(dsl, 3).useExactScan(ref)).isTrue();
    assertThat(new PgRowSearchIndex(dsl, 2).useExactScan(ref)).isFalse();

    dsl.execute("ANALYZE " + DataSchema.qualify(ref.indexTable()));
    assertThat(new PgRowSearchIndex(dsl, 3).useExactScan(ref)).isTrue();
    assertThat(new PgRowSearchIndex(dsl, 2).useExactScan(ref)).isFalse();
  }

  private String explain(CompiledFilter filter, boolean exact, String vec) {
    List<Object> params = new ArrayList<>();
    String sql = index.semanticSql(ref, filter, exact, vec, 5, params);
    return dsl.transactionResult(
        cfg -> {
          var tx = DSL.using(cfg);
          tx.execute("SET LOCAL enable_seqscan = off");
          return String.join(
              "\n", tx.fetch("EXPLAIN " + sql, params.toArray()).getValues(0, String.class));
        });
  }

  @Test
  void keyword_findsTwoSyllableKoreanTerm() {
    List<RowHit> hits = index.keyword(ref, "누수", CompiledFilter.none(), 10);
    assertThat(hits).extracting(RowHit::rowId).contains(1L, 3L).doesNotContain(2L);
    assertThat(hits).allMatch(h -> h.score() > 0);
  }
}
