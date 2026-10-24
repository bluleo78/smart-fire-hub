package com.smartfirehub.dbexecutor.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartfirehub.dbexecutor.config.ExecutionTimeouts;
import com.smartfirehub.dbexecutor.store.StoreDescriptorClient;
import com.smartfirehub.dbexecutor.web.dto.LoadResponse;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * /execute/load 실행 규칙(스펙 §3.3): 검증 → OWNER 풀 한 트랜잭션 → 500행 배치 INSERT → COMMIT, 실패·시간 초과는 ROLLBACK.
 */
class LoadRunnerTest {

  private static final String STG = "STG_0123456789ABCDEF01234567";
  private static final String INSERT =
      "INSERT INTO \"GENIA_T5\".\"" + STG + "\" (\"_SEQ\", \"A\", \"B\") VALUES (?, ?, ?)";
  private static final String BASE =
      "SELECT NVL(MAX(\"_SEQ\"), 0) FROM \"GENIA_T5\".\"" + STG + "\"";

  private final FakeJdbc db = new FakeJdbc();
  private final StoreDescriptorClient descriptors = mock(StoreDescriptorClient.class);

  LoadRunnerTest() throws SQLException {
    seqBase(0);
  }

  /** 기준값 조회({@code MAX("_SEQ")})가 돌려줄 값을 정한다. */
  private void seqBase(long base) throws SQLException {
    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
    when(rs.next()).thenReturn(true);
    when(rs.getLong(1)).thenReturn(base);
    db.resultSet = rs;
  }

  private LoadRunner runner(int loadSeconds) {
    return new LoadRunner(
        db.pools,
        new ExecutionTimeouts(
            new ExecutionTimeouts.Sql(50, 55),
            new ExecutionTimeouts.Probe(30),
            new ExecutionTimeouts.Query(30),
            new ExecutionTimeouts.Load(loadSeconds)),
        descriptors);
  }

  private static List<Map<String, Object>> rows(int n) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("a", i);
      row.put("b", "v" + i);
      rows.add(row);
    }
    return rows;
  }

  private LoadResponse load(
      int totalSeconds, String table, List<Map<String, Object>> rows, Map<String, String> types) {
    try (Deadline deadline = new Deadline(totalSeconds)) {
      return runner(1800).load(FakeJdbc.DESCRIPTOR, table, rows, types, deadline);
    }
  }

  @Test
  void batchesOf500_oneTransaction_commitOnce() {
    LoadResponse res = load(60, STG, rows(1200), Map.of("a", "INTEGER", "b", "TEXT"));

    assertThat(res.success()).isTrue();
    assertThat(res.rowsLoaded()).isEqualTo(1200);
    assertThat(res.error()).isNull();
    assertThat(res.dbError()).isNull();
    assertThat(db.events)
        .containsExactly(BASE, INSERT, "BATCH 500", "BATCH 500", "BATCH 200", "COMMIT");
    // 첫 바인드는 _SEQ(기준값 0 + 행 번호), 값은 앱 타입 변환 후 바인딩된다: INTEGER 는 Long, TEXT 는 String.
    assertThat(db.binds.get(0)).isEqualTo("setObject(1,1)");
    assertThat(db.binds.get(1)).isEqualTo("setObject(2,0)");
    assertThat(db.binds.get(2)).isEqualTo("setObject(3,v0)");
    assertThat(db.binds).hasSize(3600);
  }

  /** 같은 스테이징에 두 번째 적재가 오면 _SEQ 는 현재 MAX 다음부터 이어진다(기준값은 요청당 한 번, 행마다 +1). */
  @Test
  void seqContinuesFromMax_perRowBinding() throws Exception {
    seqBase(1200);

    LoadResponse res = load(60, STG, rows(3), Map.of("a", "INTEGER", "b", "TEXT"));

    assertThat(res.success()).isTrue();
    assertThat(db.events).containsExactly(BASE, INSERT, "BATCH 3", "COMMIT");
    List<String> seqBinds = db.binds.stream().filter(b -> b.startsWith("setObject(1,")).toList();
    assertThat(seqBinds)
        .containsExactly("setObject(1,1201)", "setObject(1,1202)", "setObject(1,1203)");
  }

  /**
   * 최종 리뷰 I3: Python 실행기는 큰 출력을 청크로 나눠 같은 스테이징에 /execute/load 를 순차로 여러 번 부른다. 기준값을 고정 스텁하지 않고 "지금까지
   * 바인딩된 _SEQ 의 최댓값"을 돌려주는 MAX 로, 두 번의 적재가 1..N 다음 N+1..2N 으로 끊김 없이 이어짐을 확인한다(옮기기의 {@code ORDER BY
   * "_SEQ"} 가 입력 순서를 지키는 근거).
   */
  @Test
  void twoSequentialLoads_keepSeqIncreasingAcrossCalls() throws Exception {
    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
    when(rs.next()).thenReturn(true);
    when(rs.getLong(1)).thenAnswer(inv -> maxSeqBound());
    db.resultSet = rs;

    assertThat(load(60, STG, rows(3), Map.of("a", "INTEGER", "b", "TEXT")).success()).isTrue();
    assertThat(load(60, STG, rows(2), Map.of("a", "INTEGER", "b", "TEXT")).success()).isTrue();

    List<String> seqBinds = db.binds.stream().filter(b -> b.startsWith("setObject(1,")).toList();
    assertThat(seqBinds)
        .containsExactly(
            "setObject(1,1)",
            "setObject(1,2)",
            "setObject(1,3)",
            "setObject(1,4)",
            "setObject(1,5)");
    assertThat(db.events)
        .containsExactly(BASE, INSERT, "BATCH 3", "COMMIT", BASE, INSERT, "BATCH 2", "COMMIT");
  }

  /** 지금까지 커밋된 적재가 매긴 {@code _SEQ} 의 최댓값(없으면 0) — 실제 스테이징의 {@code NVL(MAX("_SEQ"),0)} 를 흉내 낸다. */
  private long maxSeqBound() {
    return db.binds.stream()
        .filter(b -> b.startsWith("setObject(1,"))
        .mapToLong(b -> Long.parseLong(b.substring("setObject(1,".length(), b.length() - 1)))
        .max()
        .orElse(0L);
  }

  @Test
  void batchFailure_rollsBack_neverCommits_withDbError() {
    db.batchHook =
        call -> {
          if (call == 2) {
            throw new SQLException("TBR-10007: 고유 제약 위반", "23000", -10007);
          }
        };

    LoadResponse res = load(60, STG, rows(1200), Map.of());

    assertThat(res.success()).isFalse();
    assertThat(res.rowsLoaded()).isZero();
    assertThat(res.error()).contains("TBR-10007");
    assertThat(res.dbError().code()).isEqualTo(-10007);
    assertThat(res.dbError().sqlState()).isEqualTo("23000");
    assertThat(db.events).containsExactly(BASE, INSERT, "BATCH 500", "ROLLBACK");
  }

  @Test
  void deadlineExpiredDuringBatch_cancelsAndRollsBack() throws Exception {
    db.batchHook =
        call -> {
          if (call == 1) {
            Thread.sleep(1_400); // 1초 기한을 넘긴다 — 타이머가 실행 중 문장을 cancel 한다.
          }
        };

    LoadResponse res = load(1, STG, rows(1200), Map.of());

    assertThat(res.success()).isFalse();
    assertThat(res.error()).isEqualTo("적재 시간이 1초를 넘어 취소했습니다.");
    assertThat(db.events).contains("CANCEL", "ROLLBACK").doesNotContain("COMMIT");
    // 첫 배치 뒤 기한이 지났으므로 다음 배치는 시작하지 않는다.
    assertThat(db.events).doesNotContain("BATCH 200");
  }

  @Test
  void statementTimeout_isLoadLimitCappedByRemaining() {
    load(60, STG, rows(3), Map.of());

    // 적재 제한 1800 과 남은 시간(60) 중 작은 값.
    assertThat(db.queryTimeouts).isNotEmpty();
    assertThat(db.queryTimeouts.get(0)).isBetween(59, 60);
  }

  @Test
  void invalidTable_rejectedBeforeBorrowing() {
    for (String table : new String[] {"ORDERS", "\"GENIA_T5\".\"" + STG + "\"", "OTHER." + STG}) {
      LoadResponse res = load(60, table, rows(1), Map.of());
      assertThat(res.success()).isFalse();
      assertThat(res.error()).isEqualTo(LoadTableRule.REJECTED);
    }
    assertThat(db.factoryCalls.get()).isZero();
    assertThat(db.events).isEmpty();
  }

  @Test
  void invalidColumnName_rejectedBeforeBorrowing() {
    for (String col : new String[] {"a\"b", "A", "1a", "a b", "_seq", "a".repeat(31)}) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put(col, 1);
      LoadResponse res = load(60, STG, List.of(row), Map.of());
      assertThat(res.success()).as(col).isFalse();
      assertThat(res.error()).as(col).isEqualTo(LoadRunner.BAD_COLUMN);
    }
    assertThat(db.factoryCalls.get()).isZero();
  }

  @Test
  void unconvertibleValue_failsBeforeBorrowing_withoutEchoingValue() {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("a", "비밀값-abc");

    LoadResponse res = load(60, STG, List.of(row), Map.of("a", "INTEGER"));

    assertThat(res.success()).isFalse();
    assertThat(res.error()).contains("a").contains("INTEGER").doesNotContain("비밀값");
    assertThat(db.factoryCalls.get()).isZero();
  }

  @Test
  void noRows_succeedsWithoutBorrowing() {
    LoadResponse res = load(60, STG, List.of(), Map.of());

    assertThat(res.success()).isTrue();
    assertThat(res.rowsLoaded()).isZero();
    assertThat(db.factoryCalls.get()).isZero();
  }

  @Test
  void missingKeysBecomeNull_columnsAreUnionInFirstSeenOrder() {
    Map<String, Object> r1 = new LinkedHashMap<>();
    r1.put("a", 1);
    Map<String, Object> r2 = new LinkedHashMap<>();
    r2.put("b", "x");

    LoadResponse res = load(60, STG, List.of(r1, r2), Map.of());

    assertThat(res.success()).isTrue();
    assertThat(db.events.get(1)).isEqualTo(INSERT);
    assertThat(db.binds)
        .containsExactly(
            "setObject(1,1)",
            "setObject(2,1)",
            "setNull(3,0)",
            "setObject(1,2)",
            "setNull(2,0)",
            "setObject(3,x)");
  }

  @Test
  void connectFailure_isFixedKoreanMessage() {
    db.connectFailure = new SQLException("접속 거부 host=10.0.0.1");

    LoadResponse res = load(60, STG, rows(1), Map.of());

    assertThat(res.success()).isFalse();
    assertThat(res.error()).isEqualTo(LeasedExecution.CONNECT_FAILED);
  }

  @Test
  void nullRowElement_rejectedBeforeBorrowing() {
    List<Map<String, Object>> rows = new ArrayList<>(rows(1));
    rows.add(null);

    LoadResponse res = load(60, STG, rows, Map.of());

    assertThat(res.success()).isFalse();
    assertThat(res.error()).isEqualTo(LoadRunner.BAD_ROW);
    assertThat(db.factoryCalls.get()).isZero();
  }

  @Test
  void emptyRowObject_rejectedBeforeBorrowing() {
    List<Map<String, Object>> rows = new ArrayList<>(rows(1));
    rows.add(new LinkedHashMap<>());

    LoadResponse res = load(60, STG, rows, Map.of());

    assertThat(res.success()).isFalse();
    assertThat(res.error()).isEqualTo(LoadRunner.BAD_ROW);
    assertThat(db.factoryCalls.get()).isZero();
  }
}
