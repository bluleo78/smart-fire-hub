package com.smartfirehub.dataset.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.dto.DatasetColumnRequest;
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
}
