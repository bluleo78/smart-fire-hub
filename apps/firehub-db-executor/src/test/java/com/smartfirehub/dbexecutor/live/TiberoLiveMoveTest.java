package com.smartfirehub.dbexecutor.live;

import static com.smartfirehub.dbexecutor.live.TiberoLiveAccount.exec;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dbexecutor.config.DbExecutorProperties;
import com.smartfirehub.dbexecutor.config.ExecutionTimeouts;
import com.smartfirehub.dbexecutor.sql.LoadRunner;
import com.smartfirehub.dbexecutor.sql.ProbeRunner;
import com.smartfirehub.dbexecutor.sql.QueryRunner;
import com.smartfirehub.dbexecutor.sql.SqlRunner;
import com.smartfirehub.dbexecutor.store.HikariPoolFactory;
import com.smartfirehub.dbexecutor.store.StoreDescriptor;
import com.smartfirehub.dbexecutor.store.StoreDescriptorClient;
import com.smartfirehub.dbexecutor.store.StoreUnavailableException;
import com.smartfirehub.dbexecutor.store.TenantPools;
import com.smartfirehub.dbexecutor.web.ExecuteController;
import com.smartfirehub.dbexecutor.web.dto.LoadRequest;
import com.smartfirehub.dbexecutor.web.dto.LoadResponse;
import com.smartfirehub.dbexecutor.web.dto.SqlExecuteRequest;
import com.smartfirehub.dbexecutor.web.dto.SqlExecuteResponse;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Tibero 실서버 테스트(WD-77 Task 4) — api 가 보내는 "스테이징 → 출력 테이블 옮기기" 를 실제 서버에서 확인한다.
 *
 * <p>api {@code TiberoDmlSql.moveFromStaging}·{@code deleteAll} 이 만드는 문장과 <b>같은 문자열</b>을 쓴다(api
 * {@code TiberoDmlSqlTest.moveFromStaging_exactSql} 가 그 문자열을 고정). 출력 테이블은 api {@code
 * TiberoDdlSql.createTable} 모양(ID PK·시퀀스· CREATED_AT 기본값·_UPDATED_AT NOT NULL·읽기 전용 GRANT)으로 직접
 * 만든다.
 *
 * <p>확인 항목: REPLACE 한 트랜잭션 옮기기(ID 가 입력 순서, _UPDATED_AT 채움, GRANT 유지), 옮기기 실패 시 원본 보존, APPEND, 그리고
 * REPLACE·APPEND 동시 실행이 서로를 기다려 중복·유실이 없는지(APPEND 에 별도 잠금이 필요 없다는 근거).
 *
 * <p>환경이 없으면 클래스째 건너뛴다(실행은 {@code scripts/tibero-test.sh db-executor}). 접속 값은 어디에도 출력하지 않는다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoLiveMoveTest {

  private static final String STG = "STG_0123456789ABCDEF01234567";

  private TiberoLiveAccount account;
  private String schema;
  private Connection owner;
  private TenantPools pools;
  private ExecuteController controller;

  @BeforeAll
  void setUp() throws Exception {
    account = TiberoLiveAccount.open();
    schema = account.schema();
    owner = account.owner();
    StoreDescriptor descriptor = account.descriptor();
    pools =
        new TenantPools(
            new HikariPoolFactory(4, StoreDescriptor::owner), StoreDescriptor::owner, 8);
    account.closeBeforeDrop(pools::closeAll);
    StoreDescriptorClient descriptors =
        new StoreDescriptorClient(
            new DbExecutorProperties(
                "live-token",
                "load-tok",
                null,
                "http://127.0.0.1:9",
                60,
                new DbExecutorProperties.Pool(8, 4, 2),
                false),
            new ObjectMapper()) {
          @Override
          public StoreDescriptor get(long id, Duration budget) {
            if (id != descriptor.tenantId()) {
              throw new StoreUnavailableException("404");
            }
            return descriptor;
          }
        };
    ExecutionTimeouts timeouts =
        new ExecutionTimeouts(
            new ExecutionTimeouts.Sql(50, 55),
            new ExecutionTimeouts.Probe(30),
            new ExecutionTimeouts.Query(30),
            new ExecutionTimeouts.Load(1800));
    controller =
        new ExecuteController(
            descriptors,
            new SqlRunner(pools, timeouts, descriptors),
            new ProbeRunner(pools, timeouts, descriptors),
            new QueryRunner(pools, timeouts, descriptors),
            new LoadRunner(pools, timeouts, descriptors),
            timeouts);
  }

  @AfterAll
  void tearDown() throws Exception {
    if (account != null) {
      account.close();
    }
  }

  private String q(String name) {
    return "\"" + schema + "\".\"" + name + "\"";
  }

  /** 매 테스트마다 출력 테이블(api createTable 모양)·시퀀스·스테이징을 새로 만들고 읽기 전용 계정에 SELECT 를 준다. */
  @BeforeEach
  void recreate() throws Exception {
    TiberoLiveAccount.execIgnore(owner, "DROP TABLE " + q("ORDERS"));
    TiberoLiveAccount.execIgnore(owner, "DROP SEQUENCE " + q("SQ_ORDERS"));
    TiberoLiveAccount.execIgnore(owner, "DROP TABLE " + q(STG));
    exec(owner, "CREATE SEQUENCE " + q("SQ_ORDERS") + " START WITH 1 INCREMENT BY 1 NOCACHE");
    exec(
        owner,
        "CREATE TABLE "
            + q("ORDERS")
            + " (\"ID\" NUMBER(19) PRIMARY KEY, \"IMPORT_ID\" NUMBER(19), \"CODE\" VARCHAR2(20),"
            + " \"QTY\" NUMBER(19), \"CREATED_AT\" TIMESTAMP DEFAULT SYSTIMESTAMP,"
            + " \"_UPDATED_AT\" TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL)");
    exec(
        owner,
        "CREATE TABLE "
            + q(STG)
            + " (\"_SEQ\" NUMBER(19), \"CODE\" VARCHAR2(20), \"QTY\" NUMBER(19))");
    account.grantSelectToRo("ORDERS");
  }

  private String deleteAll() {
    return "DELETE FROM " + q("ORDERS");
  }

  /** api TiberoDmlSql.moveFromStaging 과 같은 문자열. */
  private String move(String col) {
    return "INSERT INTO "
        + q("ORDERS")
        + " (\"ID\", \""
        + col
        + "\", \"QTY\", \"_UPDATED_AT\") SELECT "
        + q("SQ_ORDERS")
        + ".NEXTVAL, \""
        + col
        + "\", \"QTY\", SYSTIMESTAMP FROM (SELECT \""
        + col
        + "\", \"QTY\" FROM "
        + q(STG)
        + " ORDER BY \"_SEQ\")";
  }

  private void load(int n, String prefix) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("code", prefix + i);
      row.put("qty", i);
      rows.add(row);
    }
    LoadResponse res =
        controller.load(
            new LoadRequest(
                account.tenantId(), STG, rows, Map.of("code", "TEXT", "qty", "INTEGER"), null));
    assertThat(res.success()).as(String.valueOf(res.error())).isTrue();
  }

  private SqlExecuteResponse run(String body, List<String> pre, Integer timeout) {
    return controller.executeSql(
        new SqlExecuteRequest(account.tenantId(), body, pre, null, timeout));
  }

  private void seedOld(int n) throws SQLException {
    for (int i = 0; i < n; i++) {
      exec(
          owner,
          "INSERT INTO "
              + q("ORDERS")
              + " (\"ID\", \"CODE\", \"QTY\") VALUES ("
              + q("SQ_ORDERS")
              + ".NEXTVAL, 'old"
              + i
              + "', 0)");
    }
  }

  private long count(String where) throws SQLException {
    try (Statement st = owner.createStatement();
        ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + q("ORDERS") + " WHERE " + where)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  @Test
  void replace_movesInOneTransaction_idsFollowInputOrder_grantsKept() throws Exception {
    seedOld(2);
    load(300, "n");

    SqlExecuteResponse res = run(move("CODE"), List.of(deleteAll()), 120);

    System.out.println("LIVE move replace success=" + res.success());
    assertThat(res.success()).as(String.valueOf(res.error())).isTrue();
    assertThat(count("1 = 1")).isEqualTo(300);
    assertThat(count("\"CODE\" LIKE 'old%'")).isZero();
    assertThat(count("\"_UPDATED_AT\" IS NULL")).isZero();
    assertThat(count("\"CREATED_AT\" IS NOT NULL")).isEqualTo(300);
    // ID 오름차순이 적재(입력) 순서와 같다 — 적재가 채운 _SEQ 순서.
    try (Statement st = owner.createStatement();
        ResultSet rs = st.executeQuery("SELECT \"QTY\" FROM " + q("ORDERS") + " ORDER BY \"ID\"")) {
      int expected = 0;
      while (rs.next()) {
        assertThat(rs.getInt(1)).isEqualTo(expected++);
      }
      assertThat(expected).isEqualTo(300);
    }
    // 같은 테이블에 넣었으므로 읽기 전용 계정의 SELECT 권한이 유지된다.
    try (Connection ro = account.openRo();
        Statement st = ro.createStatement();
        ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + q("ORDERS"))) {
      rs.next();
      assertThat(rs.getLong(1)).isEqualTo(300);
    }
  }

  @Test
  void replace_moveFails_originalRowsStay() throws Exception {
    seedOld(2);
    load(10, "n");

    SqlExecuteResponse res = run(move("NOPE"), List.of(deleteAll()), 120);

    System.out.println(
        "LIVE move failure code=" + (res.dbError() == null ? "none" : res.dbError().code()));
    assertThat(res.success()).isFalse();
    // 비우기 + 옮기기가 한 트랜잭션이라 비우기도 롤백된다.
    assertThat(count("1 = 1")).isEqualTo(2);
    assertThat(count("\"CODE\" LIKE 'old%'")).isEqualTo(2);
  }

  @Test
  void append_keepsOldRows_idsContinue() throws Exception {
    seedOld(2);
    load(5, "n");

    SqlExecuteResponse res = run(move("CODE"), List.of(), 120);

    assertThat(res.success()).as(String.valueOf(res.error())).isTrue();
    assertThat(count("1 = 1")).isEqualTo(7);
    assertThat(count("\"CODE\" LIKE 'old%'")).isEqualTo(2);
  }

  /** REPLACE 선행 잠금이 걸린 동안 APPEND 는 기다렸다가 새 내용 뒤에 붙는다(별도 잠금 불필요의 근거 1). */
  @Test
  void append_waitsForExclusiveLock_thenAppendsAfterReplace() throws Exception {
    seedOld(3);
    load(4, "n");
    try (Connection other = account.descriptorOwnerConnection()) {
      other.setAutoCommit(false);
      exec(other, "LOCK TABLE " + q("ORDERS") + " IN EXCLUSIVE MODE");
      CompletableFuture<SqlExecuteResponse> append =
          CompletableFuture.supplyAsync(() -> run(move("CODE"), List.of(), 120));
      Thread.sleep(3_000);
      assertThat(append.isDone()).as("EXCLUSIVE 잠금 중에는 APPEND INSERT 가 기다려야 한다").isFalse();
      // 잠금 쥔 쪽이 REPLACE 처럼 비우고 새 행 하나를 넣고 커밋한다.
      exec(other, "DELETE FROM " + q("ORDERS"));
      exec(
          other,
          "INSERT INTO "
              + q("ORDERS")
              + " (\"ID\", \"CODE\", \"QTY\") VALUES ("
              + q("SQ_ORDERS")
              + ".NEXTVAL, 'replaced', 0)");
      other.commit();
      SqlExecuteResponse res = append.get(60, TimeUnit.SECONDS);
      assertThat(res.success()).as(String.valueOf(res.error())).isTrue();
    }
    assertThat(count("\"CODE\" = 'replaced'")).isEqualTo(1);
    assertThat(count("1 = 1")).isEqualTo(5);
    assertThat(count("\"CODE\" LIKE 'old%'")).isZero();
  }

  /** 진행 중인(미커밋) APPEND INSERT 가 있으면 REPLACE 의 LOCK 이 그 커밋을 기다리고, 커밋된 행까지 지운다(근거 2). */
  @Test
  void replace_waitsForUncommittedAppend_thenDeletesItToo() throws Exception {
    seedOld(1);
    load(4, "n");
    try (Connection other = account.descriptorOwnerConnection()) {
      other.setAutoCommit(false);
      exec(
          other,
          "INSERT INTO "
              + q("ORDERS")
              + " (\"ID\", \"CODE\", \"QTY\") VALUES ("
              + q("SQ_ORDERS")
              + ".NEXTVAL, 'appended', 0)");
      CompletableFuture<SqlExecuteResponse> replace =
          CompletableFuture.supplyAsync(() -> run(move("CODE"), List.of(deleteAll()), 120));
      Thread.sleep(3_000);
      assertThat(replace.isDone()).as("미커밋 INSERT 가 있으면 REPLACE 의 LOCK 이 기다려야 한다").isFalse();
      other.commit();
      SqlExecuteResponse res = replace.get(60, TimeUnit.SECONDS);
      assertThat(res.success()).as(String.valueOf(res.error())).isTrue();
    }
    // 앞선 APPEND 행도 REPLACE 가 지웠고, 스테이징 4행만 남는다.
    assertThat(count("1 = 1")).isEqualTo(4);
    assertThat(count("\"CODE\" = 'appended'")).isZero();
  }
}
