package com.smartfirehub.dataset.rowsearch;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
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

  @Test
  void keyword_findsTwoSyllableKoreanTerm() {
    List<RowHit> hits = index.keyword(ref, "누수", CompiledFilter.none(), 10);
    assertThat(hits).extracting(RowHit::rowId).contains(1L, 3L).doesNotContain(2L);
    assertThat(hits).allMatch(h -> h.score() > 0);
  }
}
