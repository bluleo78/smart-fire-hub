package com.smartfirehub.dataset.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.dto.ColumnStatsResponse;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.exception.RowNotFoundException;
import com.smartfirehub.dataset.service.DataTableRowService.UpsertResult;
import com.smartfirehub.support.IntegrationTestBase;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 데이터셋 저장소 백엔드 계약 테스트 스위트(WD-72).
 *
 * <p>{@link DataTableStore}·{@link DataRowStore} 구현이 백엔드와 무관하게 지켜야 하는 관측 가능한 동작을 고정한다. 현행
 * PostgreSQL 동작을 그대로 기술하며, 백엔드별 차이는 {@link #emptyStringIsNull()} 같은 훅으로만 표현한다.
 * Tibero(WD-74)·MySQL(WD-82) 구현은 이 클래스를 상속해 세 추상 메서드만 채우면 같은 계약을 검증받는다.
 *
 * <p>클래스 레벨 {@code @Transactional} 을 쓰지 않는다 — 외부 DB 는 Spring 트랜잭션 롤백 범위 밖이므로, 각 테스트가 만든 테이블은 {@link
 * #정리()}에서 직접 DROP 한다.
 */
public abstract class DataStoreContractTest extends IntegrationTestBase {

  /** 구현이 담당하는 테이블 DDL 저장소. */
  protected abstract DataTableStore tableStore();

  /** 구현이 담당하는 행 저장소. */
  protected abstract DataRowStore rowStore();

  /** 백엔드 이름 규칙에 맞는 고유 테이블명을 만든다. */
  protected abstract String newTableName();

  /** 빈 문자열이 NULL 로 저장되는 백엔드(Tibero)는 true 로 재정의한다. */
  protected boolean emptyStringIsNull() {
    return false;
  }

  /** 계약 테스트용 컬럼 목록(데이터 컬럼만, 시스템 컬럼 제외). */
  private static final List<String> COLUMNS = List.of("name", "qty", "price", "active", "born");

  /**
   * 행 입력·조회 시 쓰는 컬럼 타입 힌트(플레이스홀더 캐스팅 판별용).
   *
   * <p>기본값은 운영 호출자({@code DatasetDataService.buildColumnTypes})가 데이터셋 컬럼 메타데이터의 {@code dataType} 을
   * 그대로 넘기는 것과 같은 값이다 — 예컨대 소수는 {@code NUMERIC} 이 아니라 {@code DECIMAL}. 백엔드가 다른 힌트 어휘를 쓰면 재정의한다.
   */
  protected Map<String, String> columnTypes() {
    return Map.of(
        "name", "TEXT",
        "qty", "INTEGER",
        "price", "DECIMAL",
        "active", "BOOLEAN",
        "born", "DATE");
  }

  /** 트랜잭션 경계가 필요한 계약(교체 중 실패·read-your-writes)을 검증하는 템플릿. */
  @Autowired private TransactionTemplate contractTx;

  private static final Logger log = LoggerFactory.getLogger(DataStoreContractTest.class);

  /** 테스트가 만든 테이블·staging 의 정리 동작. 생성 직전에 등록하고 역순(staging → 테이블)으로 실행한다. */
  private final List<Runnable> cleanups = new ArrayList<>();

  /** 등록된 정리 동작을 역순으로 실행한다. 실패해도 나머지를 계속하며 경고만 남긴다(생성 전에 실패했을 수 있다). */
  @AfterEach
  void 정리() {
    for (int i = cleanups.size() - 1; i >= 0; i--) {
      try {
        cleanups.get(i).run();
      } catch (RuntimeException e) {
        log.warn("계약 테스트 정리 실패 — 무시하고 계속한다", e);
      }
    }
    cleanups.clear();
  }

  /** 계약용 테이블을 만든다. pkOnName 이 true 면 name 을 PK 로 둔다. */
  private String createContractTable(boolean pkOnName) {
    String table = newTableName();
    // DDL 이 도중에 실패해도 남은 조각을 정리하도록 먼저 등록한다.
    cleanups.add(() -> tableStore().dropTable(table));
    tableStore()
        .createTable(
            table,
            List.of(
                new DatasetColumnRequest("name", "Name", "TEXT", null, true, false, null, pkOnName),
                new DatasetColumnRequest("qty", "Qty", "INTEGER", null, true, false, null),
                new DatasetColumnRequest("price", "Price", "DECIMAL", null, true, false, null),
                new DatasetColumnRequest("active", "Active", "BOOLEAN", null, true, false, null),
                new DatasetColumnRequest("born", "Born", "DATE", null, true, false, null)));
    return table;
  }

  /** 행 하나를 만든다. */
  private static Map<String, Object> row(
      String name, Integer qty, String price, Boolean active, String born) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("name", name);
    m.put("qty", qty);
    m.put("price", price == null ? null : new BigDecimal(price));
    m.put("active", active);
    m.put("born", born == null ? null : LocalDate.parse(born));
    return m;
  }

  /** INTEGER 컬럼은 PG 에서 BIGINT(Long), 다른 백엔드에서는 BigDecimal 일 수 있어 정수값으로만 비교한다. */
  private static long asLong(Object value) {
    return ((Number) value).longValue();
  }

  /** DATE 값을 드라이버별 반환 타입(LocalDate·java.sql.Date·문자열)과 무관하게 LocalDate 로 바꾼다. */
  private static LocalDate asLocalDate(Object value) {
    if (value instanceof LocalDate d) {
      return d;
    }
    if (value instanceof java.sql.Date d) {
      return d.toLocalDate();
    }
    return LocalDate.parse(value.toString().substring(0, 10));
  }

  /** 전체 컬럼을 id 순으로 조회한다. */
  private List<Map<String, Object>> queryAll(String table) {
    return rowStore().queryData(table, COLUMNS, null, 0, 100, null, "ASC", columnTypes());
  }

  @Test
  void 테이블_생성_후_빈_조회() {
    String table = createContractTable(false);

    assertThat(rowStore().countRows(table, COLUMNS, null, columnTypes())).isZero();
    assertThat(queryAll(table)).isEmpty();
  }

  @Test
  void 일괄_입력_후_조회_정렬_페이징() {
    String table = createContractTable(false);
    // 일부러 qty 순서를 섞어 입력해 정렬이 실제로 적용되는지 본다.
    rowStore()
        .insertBatch(
            table,
            COLUMNS,
            List.of(
                row("e", 50, "5.5", true, "2020-05-05"),
                row("b", 20, "2.5", false, "2020-02-02"),
                row("d", 40, "4.5", true, "2020-04-04"),
                row("a", 10, "1.5", false, "2020-01-01"),
                row("c", 30, "3.5", true, "2020-03-03")),
            columnTypes());

    assertThat(rowStore().countRows(table, COLUMNS, null, columnTypes())).isEqualTo(5);

    // qty 오름차순, 2건씩: 0페이지=10·20, 1페이지=30·40
    List<Map<String, Object>> page1 =
        rowStore().queryData(table, COLUMNS, null, 1, 2, "qty", "ASC", columnTypes());
    assertThat(page1).extracting(r -> asLong(r.get("qty"))).containsExactly(30L, 40L);
    assertThat(page1).extracting(r -> r.get("name")).containsExactly("c", "d");
  }

  @Test
  void 검색_필터는_대소문자_무시() {
    String table = createContractTable(false);
    rowStore()
        .insertBatch(
            table,
            COLUMNS,
            List.of(row("Apple", 1, "1", true, "2020-01-01"), row("Banana", 2, "2", true, null)),
            columnTypes());

    List<Map<String, Object>> found =
        rowStore().queryData(table, COLUMNS, "apple", 0, 10, null, "ASC", columnTypes());

    assertThat(found).hasSize(1);
    assertThat(found.get(0).get("name")).isEqualTo("Apple");
    assertThat(rowStore().countRows(table, COLUMNS, "apple", columnTypes())).isEqualTo(1);
  }

  @Test
  void upsert_는_삽입과_갱신을_구분해_센다() {
    String table = createContractTable(true);
    rowStore().insertBatch(table, COLUMNS, List.of(row("x", 1, "1", true, null)), columnTypes());

    // 기존 PK "x" 는 갱신, 새 PK "y" 는 삽입
    UpsertResult result =
        rowStore()
            .upsertBatch(
                table,
                COLUMNS,
                List.of("name"),
                List.of(row("x", 99, "9", false, null), row("y", 2, "2", true, null)),
                null,
                columnTypes());

    assertThat(result).isEqualTo(new UpsertResult(1, 1));
    assertThat(rowStore().countRows(table, COLUMNS, null, columnTypes())).isEqualTo(2);
    Map<String, Object> x =
        queryAll(table).stream().filter(r -> "x".equals(r.get("name"))).findFirst().orElseThrow();
    assertThat(asLong(x.get("qty"))).isEqualTo(99L);
  }

  @Test
  void 스테이징_교체_반영() {
    String table = createContractTable(true);
    rowStore().insertBatch(table, COLUMNS, List.of(row("old", 1, "1", true, null)), columnTypes());

    String staging = rowStore().createStagingTable(table, COLUMNS);
    cleanups.add(() -> rowStore().dropStagingTable(staging));
    rowStore()
        .insertStagingBatchWithProgress(
            staging,
            COLUMNS,
            List.of(row("n1", 10, "1", true, null), row("n2", 20, "2", false, null)),
            (done, total) -> {});

    rowStore().promoteStagingToReplace(staging, table, COLUMNS, List.of("name"));

    // 기존 행은 사라지고 스테이징 행만 남는다.
    List<Map<String, Object>> rows =
        rowStore().queryData(table, COLUMNS, null, 0, 10, "qty", "ASC", columnTypes());
    assertThat(rows).extracting(r -> r.get("name")).containsExactly("n1", "n2");
  }

  @Test
  void 빈_문자열_저장() {
    String table = createContractTable(false);
    rowStore().insertBatch(table, COLUMNS, List.of(row("", 1, "1", true, null)), columnTypes());

    Object stored = queryAll(table).get(0).get("name");

    assertThat(stored).isEqualTo(emptyStringIsNull() ? null : "");
  }

  @Test
  void 불리언_왕복() {
    String table = createContractTable(false);
    rowStore().insertBatch(table, COLUMNS, List.of(row("b", 1, "1", true, null)), columnTypes());

    assertThat(queryAll(table).get(0).get("active")).isEqualTo(Boolean.TRUE);
  }

  @Test
  void 행_수정_삭제_단건조회() {
    String table = createContractTable(false);
    Long id1 =
        rowStore()
            .insertRow(table, COLUMNS, row("one", 1, "1.5", true, "2020-01-01"), columnTypes());
    Long id2 = rowStore().insertRow(table, COLUMNS, row("two", 2, "2", false, null), columnTypes());
    assertThat(rowStore().countRows(table, COLUMNS, null, columnTypes())).isEqualTo(2);

    // 수정한 값이 단건 조회에 반영된다.
    Map<String, Object> changed = new HashMap<>(row("uno", 11, "2.50", true, "2021-02-03"));
    rowStore().updateRow(table, id1, COLUMNS, changed, columnTypes());
    Map<String, Object> got = rowStore().getRow(table, COLUMNS, id1, columnTypes());
    assertThat(got.get("name")).isEqualTo("uno");
    assertThat(asLong(got.get("qty"))).isEqualTo(11L);
    // 소수·날짜도 값 그대로 왕복한다(스케일 차이는 무시, DATE 는 드라이버 타입과 무관하게 LocalDate 로 정규화).
    assertThat((BigDecimal) got.get("price")).isEqualByComparingTo(new BigDecimal("2.5"));
    assertThat(asLocalDate(got.get("born"))).isEqualTo(LocalDate.of(2021, 2, 3));

    // 삭제 후 건수가 줄고, 삭제된 id 의 단건 조회는 RowNotFoundException 이다(DataRowStore.getRow 계약).
    assertThat(rowStore().deleteRows(table, List.of(id2))).isEqualTo(1);
    assertThat(rowStore().countRows(table, COLUMNS, null, columnTypes())).isEqualTo(1);
    assertThatThrownBy(() -> rowStore().getRow(table, COLUMNS, id2, columnTypes()))
        .isInstanceOf(RowNotFoundException.class);
  }

  /** 계약 테이블 컬럼 정의(복제·통계 입력용). 시스템 컬럼은 포함하지 않는다. */
  private static List<DatasetColumnResponse> columnDefs() {
    String[][] defs = {
      {"name", "TEXT"},
      {"qty", "INTEGER"},
      {"price", "DECIMAL"},
      {"active", "BOOLEAN"},
      {"born", "DATE"}
    };
    List<DatasetColumnResponse> list = new ArrayList<>();
    for (int i = 0; i < defs.length; i++) {
      list.add(
          new DatasetColumnResponse(
              (long) i, defs[i][0], defs[i][0], defs[i][1], null, true, false, null, i, false));
    }
    return list;
  }

  /** 결과 맵에서 대소문자 무시로 키를 찾는다(백엔드별 별칭 표기 차이 흡수). */
  private static Object valueIgnoreCase(Map<String, Object> map, String key) {
    return map.entrySet().stream()
        .filter(e -> e.getKey().equalsIgnoreCase(key))
        .map(Map.Entry::getValue)
        .findFirst()
        .orElse(null);
  }

  @Test
  void 복제_후_같은_행() {
    String source = createContractTable(false);
    rowStore()
        .insertBatch(
            source,
            COLUMNS,
            List.of(
                row("a", 1, "1.5", true, "2020-01-01"), row("b", 2, "2.5", false, "2020-02-02")),
            columnTypes());

    String target = newTableName();
    cleanups.add(() -> tableStore().dropTable(target));
    tableStore().cloneTable(source, target, COLUMNS, columnDefs());

    List<Map<String, Object>> copied =
        rowStore().queryData(target, COLUMNS, null, 0, 100, "qty", "ASC", columnTypes());
    assertThat(copied).extracting(r -> r.get("name")).containsExactly("a", "b");
    assertThat(copied).extracting(r -> asLong(r.get("qty"))).containsExactly(1L, 2L);
    assertThat((BigDecimal) copied.get(1).get("price")).isEqualByComparingTo("2.5");
    assertThat(asLocalDate(copied.get(0).get("born"))).isEqualTo(LocalDate.of(2020, 1, 1));
    // 복제본은 독립 테이블이다 — 원본에 행을 더해도 복제본은 그대로다.
    rowStore().insertBatch(source, COLUMNS, List.of(row("c", 3, "3", true, null)), columnTypes());
    assertThat(rowStore().countRows(target, COLUMNS, null, columnTypes())).isEqualTo(2);
  }

  @Test
  void 컬럼_추가_이름변경_삭제() {
    String table = createContractTable(false);
    tableStore()
        .addColumn(
            table, new DatasetColumnRequest("memo", "Memo", "TEXT", null, true, false, null));
    List<String> withMemo = new ArrayList<>(COLUMNS);
    withMemo.add("memo");
    Map<String, String> types = new HashMap<>(columnTypes());
    types.put("memo", "TEXT");
    Map<String, Object> r = row("m", 1, "1", true, null);
    r.put("memo", "hello");
    rowStore().insertBatch(table, withMemo, List.of(r), types);
    assertThat(
            rowStore()
                .queryData(table, withMemo, null, 0, 10, null, "ASC", types)
                .get(0)
                .get("memo"))
        .isEqualTo("hello");

    // 이름 변경 후에는 새 이름으로 같은 값이 읽힌다.
    tableStore().renameColumn(table, "memo", "note");
    List<String> withNote = new ArrayList<>(COLUMNS);
    withNote.add("note");
    Map<String, String> noteTypes = new HashMap<>(columnTypes());
    noteTypes.put("note", "TEXT");
    assertThat(
            rowStore()
                .queryData(table, withNote, null, 0, 10, null, "ASC", noteTypes)
                .get(0)
                .get("note"))
        .isEqualTo("hello");

    // 삭제 후에는 그 컬럼을 읽을 수 없고 기존 컬럼은 그대로다.
    tableStore().dropColumn(table, "note");
    assertThatThrownBy(
            () -> rowStore().queryData(table, withNote, null, 0, 10, null, "ASC", noteTypes))
        .isInstanceOf(RuntimeException.class);
    assertThat(queryAll(table)).hasSize(1);
  }

  @Test
  void 컬럼_통계() {
    String table = createContractTable(false);
    rowStore()
        .insertBatch(
            table,
            COLUMNS,
            List.of(
                row("a", 10, "1", true, null),
                row("a", 20, "2", true, null),
                row("b", 30, "3", false, null),
                row("c", null, "4", false, null)),
            columnTypes());

    List<ColumnStatsResponse> stats = tableStore().getColumnStats(table, columnDefs());

    ColumnStatsResponse qty =
        stats.stream().filter(s -> "qty".equals(s.columnName())).findFirst().orElseThrow();
    assertThat(qty.totalCount()).isEqualTo(4);
    assertThat(qty.nullCount()).isEqualTo(1);
    assertThat(qty.distinctCount()).isEqualTo(3);
    assertThat(new BigDecimal(qty.minValue())).isEqualByComparingTo("10");
    assertThat(new BigDecimal(qty.maxValue())).isEqualByComparingTo("30");
    assertThat(qty.avgValue()).isEqualTo(20.0);
    assertThat(qty.sampled()).isFalse();
    ColumnStatsResponse name =
        stats.stream().filter(s -> "name".equals(s.columnName())).findFirst().orElseThrow();
    assertThat(name.distinctCount()).isEqualTo(3);
    assertThat(name.topValues().get(0).value()).isEqualTo("a");
    assertThat(name.topValues().get(0).count()).isEqualTo(2);
  }

  @Test
  void 스테이징_upsert_반영_마지막_행_우선() {
    String table = createContractTable(true);
    rowStore().insertBatch(table, COLUMNS, List.of(row("x", 1, "1", true, null)), columnTypes());

    String staging = rowStore().createStagingTable(table, COLUMNS);
    cleanups.add(() -> rowStore().dropStagingTable(staging));
    // 같은 PK "y" 가 두 번 — 마지막 값(qty=22)이 이겨야 하고, 기존 PK "x" 는 갱신된다.
    rowStore()
        .insertStagingBatchWithProgress(
            staging,
            COLUMNS,
            List.of(
                row("x", 100, "1", true, null),
                row("y", 21, "2", false, null),
                row("y", 22, "3", false, null)),
            (done, total) -> {});

    UpsertResult result =
        rowStore().promoteStagingToUpsert(staging, table, COLUMNS, List.of("name"), null);

    assertThat(result).isEqualTo(new UpsertResult(1, 1));
    assertThat(rowStore().countRows(table, COLUMNS, null, columnTypes())).isEqualTo(2);
    Map<String, Long> qtyByName = new HashMap<>();
    queryAll(table).forEach(r -> qtyByName.put((String) r.get("name"), asLong(r.get("qty"))));
    assertThat(qtyByName).containsEntry("x", 100L).containsEntry("y", 22L);
  }

  @Test
  void NULL_존재_검사() {
    String table = createContractTable(false);
    rowStore()
        .insertBatch(table, COLUMNS, List.of(row("a", 1, "1", true, "2020-01-01")), columnTypes());
    assertThat(rowStore().hasNulls(table, "born")).isFalse();

    rowStore().insertBatch(table, COLUMNS, List.of(row("b", 2, "2", true, null)), columnTypes());
    assertThat(rowStore().hasNulls(table, "born")).isTrue();
  }

  @Test
  void 유일성_검사와_중복_행() {
    String table = createContractTable(false);
    rowStore()
        .insertBatch(
            table,
            COLUMNS,
            List.of(row("a", 1, "1", true, null), row("b", 2, "2", true, null)),
            columnTypes());
    assertThat(rowStore().checkDataUniqueness(table, List.of("name"))).isTrue();
    assertThat(rowStore().findDuplicateRows(table, List.of("name"), 10)).isEmpty();

    rowStore().insertBatch(table, COLUMNS, List.of(row("a", 3, "3", true, null)), columnTypes());

    assertThat(rowStore().checkDataUniqueness(table, List.of("name"))).isFalse();
    List<Map<String, Object>> dups = rowStore().findDuplicateRows(table, List.of("name"), 10);
    assertThat(dups).hasSize(1);
    assertThat(valueIgnoreCase(dups.get(0), "name")).isEqualTo("a");
    assertThat(asLong(valueIgnoreCase(dups.get(0), "duplicate_count"))).isEqualTo(2L);
  }

  /** 교체(비우기+입력) 도중 예외가 나면 트랜잭션이 롤백되어 기존 행이 그대로 남아야 한다(데이터 소실 방지). */
  @Test
  void 교체_중_실패하면_기존_행_유지() {
    String table = createContractTable(false);
    rowStore()
        .insertBatch(
            table,
            COLUMNS,
            List.of(row("old1", 1, "1", true, null), row("old2", 2, "2", true, null)),
            columnTypes());

    assertThatThrownBy(
            () ->
                contractTx.executeWithoutResult(
                    status -> {
                      rowStore().truncateTable(table);
                      rowStore()
                          .insertBatch(
                              table,
                              COLUMNS,
                              List.of(row("new1", 9, "9", true, null)),
                              columnTypes());
                      throw new IllegalStateException("교체 도중 실패");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("교체 도중 실패");

    assertThat(queryAll(table))
        .extracting(r -> r.get("name"))
        .containsExactlyInAnyOrder("old1", "old2");
  }

  /** 같은 트랜잭션 안에서 insertRow 직후 getRow 가 방금 넣은 미커밋 행을 봐야 한다(read-your-writes). */
  @Test
  void 행_추가_직후_단건조회() {
    String table = createContractTable(false);

    Map<String, Object> got =
        contractTx.execute(
            status -> {
              Long id =
                  rowStore()
                      .insertRow(table, COLUMNS, row("fresh", 7, "7.5", true, null), columnTypes());
              return rowStore().getRow(table, COLUMNS, id, columnTypes());
            });

    assertThat(got.get("name")).isEqualTo("fresh");
    assertThat(asLong(got.get("qty"))).isEqualTo(7L);
    // 커밋 후에도 남아 있다.
    assertThat(rowStore().countRows(table, COLUMNS, null, columnTypes())).isEqualTo(1);
  }
}
