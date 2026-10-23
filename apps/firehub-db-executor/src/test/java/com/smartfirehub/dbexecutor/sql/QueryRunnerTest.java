package com.smartfirehub.dbexecutor.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartfirehub.dbexecutor.config.ExecutionTimeouts;
import com.smartfirehub.dbexecutor.store.StoreDescriptor;
import com.smartfirehub.dbexecutor.store.StoreDescriptorClient;
import com.smartfirehub.dbexecutor.web.dto.QueryResponse;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** /execute/query: RO 풀로 조회만 실행, 끝 세미콜론 제거, 행 상한·truncated, 트랜잭션은 항상 롤백으로 닫힘. */
class QueryRunnerTest {

  private final FakeJdbc db = new FakeJdbc();
  private final StoreDescriptorClient descriptors = mock(StoreDescriptorClient.class);

  QueryRunnerTest() throws SQLException {}

  private QueryRunner runner(int timeoutSeconds) {
    return new QueryRunner(
        db.roPools,
        new ExecutionTimeouts(
            new ExecutionTimeouts.Sql(50, 55),
            new ExecutionTimeouts.Probe(30),
            new ExecutionTimeouts.Query(timeoutSeconds)),
        descriptors);
  }

  private QueryResponse run(StoreDescriptor d, String query, int maxRows) {
    try (Deadline deadline = new Deadline(30)) {
      return runner(30).run(d, query, maxRows, deadline);
    }
  }

  private QueryResponse run(String query, int maxRows) {
    return run(FakeJdbc.DESCRIPTOR, query, maxRows);
  }

  /** 컬럼 하나(대문자 별칭 AMOUNT)에 n(>=1) 행을 주는 가짜 결과 집합. */
  private ResultSet rows(int n) throws SQLException {
    ResultSet rs = mock(ResultSet.class);
    ResultSetMetaData md = mock(ResultSetMetaData.class);
    when(rs.getMetaData()).thenReturn(md);
    when(md.getColumnCount()).thenReturn(1);
    when(md.getColumnLabel(1)).thenReturn("AMOUNT");
    when(md.getColumnType(1)).thenReturn(Types.NUMERIC);
    when(md.getColumnTypeName(1)).thenReturn("NUMBER");
    when(md.getPrecision(1)).thenReturn(18);
    when(md.getScale(1)).thenReturn(2);
    when(rs.next()).thenReturn(true, trailing(n));
    when(rs.getBigDecimal(1)).thenReturn(new BigDecimal("1.5"));
    return rs;
  }

  /** n 행이면 첫 true 이후 (n-1)개의 true 와 마지막 false 가 이어진다. */
  private static Boolean[] trailing(int n) {
    Boolean[] out = new Boolean[n];
    for (int i = 0; i < n - 1; i++) {
      out[i] = true;
    }
    out[n - 1] = false;
    return out;
  }

  @Test
  void select_returnsRowsColumnsAndTruncated() throws SQLException {
    db.resultSet = rows(3);

    QueryResponse res = run("SELECT amount FROM t", 3);

    assertThat(res.success()).isTrue();
    assertThat(res.rowCount()).isEqualTo(3);
    assertThat(res.truncated()).isTrue();
    // ValueConverter.columnName 규칙: 대문자 별칭은 소문자로.
    assertThat(res.columns()).containsExactly("amount");
    assertThat(res.rows()).hasSize(3);
    assertThat(db.maxRows).containsExactly(3);
    assertThat(db.queryTimeouts).containsExactly(30);
  }

  @Test
  void maxRowsAboveRowCount_isNotTruncated() throws SQLException {
    db.resultSet = rows(3);

    QueryResponse res = run("SELECT amount FROM t", 5);

    assertThat(res.success()).isTrue();
    assertThat(res.rowCount()).isEqualTo(3);
    assertThat(res.truncated()).isFalse();
    assertThat(db.maxRows).containsExactly(5);
  }

  @Test
  void trailingSemicolon_isStripped() throws SQLException {
    db.resultSet = rows(1);
    run("SELECT 1 FROM DUAL;", 10);
    assertThat(db.events.get(0)).isEqualTo("SELECT 1 FROM DUAL");
  }

  /** 끝이 `--` 주석 뒤 `;` 인 조회도 세미콜론(과 앞뒤 공백)만 떼고 실행한다(기존 validatedBody 규칙). */
  @Test
  void trailingSemicolonAfterLineComment_isStripped() throws SQLException {
    db.resultSet = rows(1);
    QueryResponse res = run("SELECT 1 FROM DUAL -- c\n;", 10);
    assertThat(res.success()).isTrue();
    assertThat(db.events.get(0)).isEqualTo("SELECT 1 FROM DUAL -- c");
  }

  @Test
  void rejectsNonQuery_beforeBorrowing() {
    for (String sql :
        new String[] {
          "UPDATE GENIA_T5.T SET A=1",
          "INSERT INTO GENIA_T5.T VALUES (1)",
          "DELETE FROM GENIA_T5.T",
          "MERGE INTO GENIA_T5.T USING DUAL ON (1=1) WHEN MATCHED THEN UPDATE SET A=1",
        }) {
      QueryResponse res = run(sql, 10);
      assertThat(res.success()).as(sql).isFalse();
      assertThat(res.error()).as(sql).isEqualTo("Tibero 저장소의 분석 SQL 은 조회(SELECT·WITH)만 실행합니다.");
    }
    assertThat(db.factoryCalls.get()).isZero();
  }

  @Test
  void acceptsWith() throws SQLException {
    db.resultSet = rows(1);
    QueryResponse res = run("WITH x AS (SELECT 1 a FROM DUAL) SELECT a FROM x", 10);
    assertThat(res.success()).isTrue();
    assertThat(db.events.get(0)).startsWith("WITH x AS");
  }

  /** 괄호로 시작하는 UNION 질의도 첫 키워드가 SELECT 로 인정돼 그대로 실행된다. */
  @Test
  void leadingParenUnion_isAcceptedAndExecutedAsIs() throws SQLException {
    String q = "(SELECT 1 a FROM DUAL) UNION SELECT 2 a FROM DUAL";
    db.resultSet = rows(1);
    QueryResponse res = run(q, 10);
    assertThat(res.success()).isTrue();
    assertThat(db.events.get(0)).isEqualTo(q);
  }

  /** 드라이버 메시지에 RO 비밀번호가 섞여도 응답 error 에는 나오지 않는다. */
  @Test
  void roPasswordInDriverMessage_isMasked() {
    db.handler =
        sql -> {
          throw new SQLException("login failed for ro-secret", "42000", -1);
        };
    QueryResponse res = run("SELECT 1 FROM DUAL", 10);
    assertThat(res.success()).isFalse();
    assertThat(res.error()).doesNotContain("ro-secret").contains("****");
    assertThat(res.dbError().message()).doesNotContain("ro-secret");
  }

  @Test
  void missingRoAccount_doesNotBorrow() {
    StoreDescriptor d = FakeJdbc.DESCRIPTOR;
    StoreDescriptor noRo =
        new StoreDescriptor(
            d.tenantId(),
            d.backend(),
            d.host(),
            d.port(),
            d.database(),
            d.schema(),
            d.owner(),
            null);

    QueryResponse res = run(noRo, "SELECT 1 FROM DUAL", 10);

    assertThat(res.success()).isFalse();
    assertThat(res.error()).isEqualTo("Tibero 읽기 전용 계정이 준비되지 않았습니다.");
    assertThat(db.factoryCalls.get()).isZero();
    // 캐시된 기술자를 지워, 그 뒤 준비된 RO 계정을 다음 요청이 TTL 을 기다리지 않고 받게 한다(F8).
    org.mockito.Mockito.verify(descriptors).invalidate(5L);
  }

  /** RO 계정이 있으면(정상 경로) 기술자 캐시를 지우지 않는다. */
  @Test
  void roPresent_doesNotInvalidateDescriptor() throws SQLException {
    db.resultSet = rows(1);
    assertThat(run("SELECT 1 FROM DUAL", 10).success()).isTrue();
    org.mockito.Mockito.verify(descriptors, org.mockito.Mockito.never()).invalidate(5L);
  }

  /**
   * SELECT … FOR UPDATE 는 첫 키워드가 SELECT 여도 커넥션을 빌리기 전에 거부한다 — SELECT 권한만 있는 RO 계정도 행을 잠글 수 있다(F2).
   * 대소문자·줄바꿈·블록 주석으로 띄운 형태도 같다.
   */
  @Test
  void selectForUpdate_isRejectedBeforeBorrowing() {
    for (String sql :
        new String[] {
          "SELECT * FROM t FOR UPDATE",
          "select a from t for update nowait",
          "SELECT a FROM t\nFOR\n  UPDATE OF a",
          "SELECT a FROM t FOR/* x */UPDATE",
          "WITH x AS (SELECT 1 a FROM DUAL) SELECT a FROM x FOR UPDATE",
        }) {
      QueryResponse res = run(sql, 10);
      assertThat(res.success()).as(sql).isFalse();
      assertThat(res.error()).as(sql).isEqualTo("Tibero 저장소의 분석 SQL 에서는 FOR UPDATE 를 쓸 수 없습니다.");
    }
    assertThat(db.factoryCalls.get()).isZero();
  }

  /** 문자열·주석·큰따옴표 식별자 안의 "for update" 는 코드가 아니므로 조회로 실행된다. */
  @Test
  void forUpdateInsideLiteralCommentOrQuotedIdentifier_isAccepted() throws SQLException {
    for (String sql :
        new String[] {
          "SELECT 'for update' FROM DUAL",
          "SELECT 1 FROM DUAL -- for update",
          "SELECT 1 /* FOR UPDATE */ FROM DUAL",
          "SELECT 1 \"FOR UPDATE\" FROM DUAL",
          "SELECT q'[for update]' FROM DUAL",
        }) {
      db.resultSet = rows(1);
      QueryResponse res = run(sql, 10);
      assertThat(res.success()).as(sql).isTrue();
    }
  }

  /** FOR 와 UPDATE 가 이어지지 않으면(사이에 다른 단어·기호) FOR UPDATE 가 아니다. */
  @Test
  void hasForUpdate_requiresAdjacentWords() {
    assertThat(QueryRunner.hasForUpdate("SELECT \"FOR\" , update_at FROM t")).isFalse();
    assertThat(QueryRunner.hasForUpdate("SELECT for_x, update FROM t")).isFalse();
    assertThat(QueryRunner.hasForUpdate("SELECT a FROM t FOR UPDATE")).isTrue();
  }

  /**
   * JOIN 의 같은 이름 컬럼(REGION 두 개)이 행 Map 에서 덮이지 않는다 — api ResultColumnNames.unique 와 같은 규칙으로 뒤 컬럼이
   * region_2 가 되고 두 값이 모두 남는다(F1, WD-85).
   */
  @Test
  void duplicateColumnNames_areMadeUnique_andBothValuesKept() throws SQLException {
    ResultSet rs = mock(ResultSet.class);
    ResultSetMetaData md = mock(ResultSetMetaData.class);
    when(rs.getMetaData()).thenReturn(md);
    when(md.getColumnCount()).thenReturn(2);
    when(md.getColumnLabel(1)).thenReturn("REGION");
    when(md.getColumnLabel(2)).thenReturn("REGION");
    when(md.getColumnType(1)).thenReturn(Types.VARCHAR);
    when(md.getColumnType(2)).thenReturn(Types.VARCHAR);
    when(rs.next()).thenReturn(true, false);
    when(rs.getString(1)).thenReturn("서울");
    when(rs.getString(2)).thenReturn("부산");
    db.resultSet = rs;

    QueryResponse res = run("SELECT a.region, b.region FROM t a JOIN t b ON a.id = b.id", 10);

    assertThat(res.success()).isTrue();
    assertThat(res.columns()).containsExactly("region", "region_2");
    assertThat(res.rows()).hasSize(1);
    assertThat(res.rows().get(0)).containsEntry("region", "서울").containsEntry("region_2", "부산");
  }

  @Test
  void maxRowsOutOfRange() {
    for (int bad : new int[] {0, 10_001, -1}) {
      QueryResponse res = run("SELECT 1 FROM DUAL", bad);
      assertThat(res.success()).as("maxRows=" + bad).isFalse();
      assertThat(res.error()).isEqualTo("요청 형식이 올바르지 않습니다.");
    }
    assertThat(db.factoryCalls.get()).isZero();
    assertThat(run("SELECT 1 FROM DUAL", 10_000).error()).isNotEqualTo("요청 형식이 올바르지 않습니다.");
  }

  @Test
  void timeout_returnsTimeoutMessage() {
    db.handler =
        sql -> {
          // cancel() 이 불릴 때까지 막혀 있다가, 드라이버처럼 취소 오류를 던진다.
          if (!db.cancelled.await(10, TimeUnit.SECONDS)) {
            throw new AssertionError("cancel 이 호출되지 않았다");
          }
          throw new SQLException("statement cancelled", "72000", -1013);
        };
    QueryResponse res;
    try (Deadline deadline = new Deadline(1)) {
      res = runner(1).run(FakeJdbc.DESCRIPTOR, "SELECT 1 FROM DUAL", 10, deadline);
    }
    assertThat(res.success()).isFalse();
    // 스펙 §3.7 시간 초과 안내와 같은 모양(api 가 그대로 보여 준다).
    assertThat(res.error()).isEqualTo("실행 시간 제한(1초)을 넘어 중단했습니다.");
    assertThat(db.events).contains("CANCEL", "ROLLBACK");
  }

  @Test
  void transaction_isRolledBackOnSuccessAndFailure_neverCommitted() throws SQLException {
    db.resultSet = rows(1);
    run("SELECT 1 FROM DUAL", 10);
    assertThat(db.events).containsExactly("SELECT 1 FROM DUAL", "ROLLBACK");

    db.events.clear();
    db.handler =
        sql -> {
          throw new SQLException("TBR-8033: not found", "42000", -8033);
        };
    QueryResponse res = run("SELECT x FROM nope", 10);
    assertThat(res.success()).isFalse();
    assertThat(res.dbError().code()).isEqualTo(-8033);
    assertThat(db.events).containsExactly("SELECT x FROM nope", "ROLLBACK");
    assertThat(db.events).doesNotContain("COMMIT");
  }

  @Test
  void connectFailure_invalidatesDescriptor() {
    db.connectFailure = new SQLException("login denied");
    QueryResponse res = run("SELECT 1 FROM DUAL", 10);
    assertThat(res.success()).isFalse();
    assertThat(res.error()).isEqualTo("테넌트 저장소에 연결하지 못했습니다.");
    org.mockito.Mockito.verify(descriptors).invalidate(5L);
  }
}
