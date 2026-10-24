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
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Tibero 실서버 테스트(WD-77 Task 3) — {@code /execute/load} 를 실제 서버의 스테이징 테이블에 돌린다.
 *
 * <p>환경이 없으면 클래스째 건너뛴다(실행은 {@code scripts/tibero-test.sh db-executor}). 전용 계정은 {@link
 * TiberoLiveAccount} 가 만들고 끝에 지운다. 접속 값은 어디에도 출력하지 않는다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoLiveLoadTest {

  /** api {@code TiberoNames.stagingTableName()} 모양의 임의 스테이징 이름. */
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
    // 풀이 계정 세션을 잡고 있으면 DROP USER 가 거절되므로 먼저 닫는다.
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

  /** 매 테스트마다 api createStaging 과 같은 모양(앞에 {@code _SEQ})의 스테이징 테이블을 새로 만든다. */
  @BeforeEach
  void recreateStaging() throws SQLException {
    TiberoLiveAccount.execIgnore(owner, "DROP TABLE \"" + schema + "\".\"" + STG + "\"");
    exec(
        owner,
        "CREATE TABLE \""
            + schema
            + "\".\""
            + STG
            + "\" (\"_SEQ\" NUMBER(19), \"ID_KEY\" NUMBER(19), \"AMOUNT\" NUMBER(18,6),"
            + " \"NAME\" VARCHAR2(20), \"FLAG\" NUMBER(1), \"D\" DATE, \"TS\" TIMESTAMP)");
  }

  private LoadResponse load(String table, List<Map<String, Object>> rows) {
    return controller.load(
        new LoadRequest(
            account.tenantId(),
            table,
            rows,
            Map.of(
                "id_key", "INTEGER",
                "amount", "DECIMAL",
                "name", "TEXT",
                "flag", "BOOLEAN",
                "d", "DATE",
                "ts", "TIMESTAMP"),
            null));
  }

  private static Map<String, Object> row(int i, String name) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id_key", i);
    row.put("amount", "12.5");
    row.put("name", name);
    row.put("flag", i % 2 == 0);
    row.put("d", "2024-03-05");
    // +09:00 은 UTC 로 환산돼 01:02:03 으로 저장된다.
    row.put("ts", "2024-03-05T10:02:03+09:00");
    return row;
  }

  private long count() throws SQLException {
    try (Statement st = owner.createStatement();
        ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM \"" + schema + "\".\"" + STG + "\"")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  @Test
  void load_1200Rows_inThreeBatches_andValuesMatchBinding() throws Exception {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (int i = 0; i < 1200; i++) {
      rows.add(row(i, "n" + i));
    }

    LoadResponse res = load(STG, rows);

    System.out.println("LIVE load success=" + res.success() + " rows=" + res.rowsLoaded());
    assertThat(res.success()).as(String.valueOf(res.error())).isTrue();
    assertThat(res.rowsLoaded()).isEqualTo(1200);
    assertThat(count()).isEqualTo(1200);
    try (Statement st = owner.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT \"AMOUNT\", \"NAME\", \"FLAG\", TO_CHAR(\"D\", 'YYYY-MM-DD'),"
                    + " TO_CHAR(\"TS\", 'YYYY-MM-DD HH24:MI:SS'), \"_SEQ\" FROM \""
                    + schema
                    + "\".\""
                    + STG
                    + "\" WHERE \"ID_KEY\" = 2")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getBigDecimal(1)).isEqualByComparingTo("12.5");
      assertThat(rs.getString(2)).isEqualTo("n2");
      assertThat(rs.getInt(3)).isEqualTo(1);
      assertThat(rs.getString(4)).isEqualTo("2024-03-05");
      assertThat(rs.getString(5)).isEqualTo("2024-03-05 01:02:03");
      // 스테이징의 _SEQ 는 입력 순번(기준값 0 + 행 번호) — id_key=2 는 세 번째 행이다.
      assertThat(rs.getLong(6)).isEqualTo(3);
    }
  }

  /** 같은 스테이징에 두 번 적재해도 _SEQ 가 이어진다(기준값 = 현재 MAX). */
  @Test
  void load_twice_seqContinues() throws Exception {
    assertThat(load(STG, List.of(row(0, "a"), row(1, "b"))).success()).isTrue();
    assertThat(load(STG, List.of(row(2, "c"))).success()).isTrue();

    try (Statement st = owner.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT \"ID_KEY\", \"_SEQ\" FROM \""
                    + schema
                    + "\".\""
                    + STG
                    + "\" ORDER BY \"_SEQ\"")) {
      for (int i = 0; i < 3; i++) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getInt(1)).isEqualTo(i);
        assertThat(rs.getLong(2)).isEqualTo(i + 1);
      }
    }
  }

  @Test
  void load_nullValues_areStoredAsNull() throws Exception {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id_key", 1);
    row.put("name", null);
    row.put("flag", null);
    row.put("ts", null);

    LoadResponse res = load(STG, List.of(row));

    assertThat(res.success()).as(String.valueOf(res.error())).isTrue();
    try (Statement st = owner.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT \"NAME\", \"FLAG\", \"TS\" FROM \"" + schema + "\".\"" + STG + "\"")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString(1)).isNull();
      rs.getInt(2);
      assertThat(rs.wasNull()).isTrue();
      assertThat(rs.getTimestamp(3)).isNull();
    }
  }

  @Test
  void load_failureInSecondBatch_rollsBackEverything() throws Exception {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (int i = 0; i < 700; i++) {
      // 600번째 행(두 번째 배치)의 이름이 VARCHAR2(20) 을 넘는다.
      rows.add(row(i, i == 600 ? "x".repeat(50) : "n" + i));
    }

    LoadResponse res = load(STG, rows);

    System.out.println(
        "LIVE load failure code=" + (res.dbError() == null ? "none" : res.dbError().code()));
    assertThat(res.success()).isFalse();
    assertThat(res.rowsLoaded()).isZero();
    assertThat(res.dbError()).isNotNull();
    assertThat(res.dbError().message()).isNotBlank();
    // 한 트랜잭션이라 첫 배치(500행)도 남지 않는다.
    assertThat(count()).isZero();
  }

  @Test
  void load_rejectsNonStagingTable_andLeavesTablesUntouched() throws Exception {
    exec(owner, "CREATE TABLE \"" + schema + "\".\"ORDERS\" (\"ID_KEY\" NUMBER(19))");

    LoadResponse res = load("ORDERS", List.of(row(1, "a")));

    assertThat(res.success()).isFalse();
    try (Statement st = owner.createStatement();
        ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM \"" + schema + "\".\"ORDERS\"")) {
      rs.next();
      assertThat(rs.getLong(1)).isZero();
    }
  }

  /**
   * Python 실행기 원격 적재(WD-77 Task 6)가 실제로 보내는 본문 — 공유 사례 {@code python-remote-load-wire.json} 의
   * {@code wire}(변환·직렬화가 끝난 값)와 {@code wireColumnTypes} 를 그대로 적재한다. 변환 불가·빈 값이 None 으로 와서 요청 전체 실패가
   * 없고, 값이 의도대로 저장되는지 본다.
   */
  @Test
  @SuppressWarnings("unchecked")
  void load_pythonRemoteLoaderWire_isAcceptedAndStoredAsIntended() throws Exception {
    Map<String, Object> fixture =
        new ObjectMapper()
            .readValue(
                java.nio.file.Path.of(
                        "../firehub-api/src/test/resources/fixtures/python-remote-load-wire.json")
                    .toFile(),
                Map.class);
    List<Map<String, Object>> rows = (List<Map<String, Object>>) fixture.get("wire");
    Map<String, String> types = (Map<String, String>) fixture.get("wireColumnTypes");

    LoadResponse res = controller.load(new LoadRequest(account.tenantId(), STG, rows, types, null));

    System.out.println("LIVE python-wire success=" + res.success() + " rows=" + res.rowsLoaded());
    assertThat(res.success()).as(String.valueOf(res.error())).isTrue();
    assertThat(res.rowsLoaded()).isEqualTo(3);
    try (Statement st = owner.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT \"ID_KEY\", \"AMOUNT\", \"NAME\", \"FLAG\", TO_CHAR(\"D\", 'YYYY-MM-DD'),"
                    + " TO_CHAR(\"TS\", 'YYYY-MM-DD HH24:MI:SS') FROM \""
                    + schema
                    + "\".\""
                    + STG
                    + "\" ORDER BY \"_SEQ\"")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getLong(1)).isEqualTo(7);
      assertThat(rs.getBigDecimal(2)).isEqualByComparingTo("12.5");
      assertThat(rs.getString(3)).isEqualTo("alpha");
      assertThat(rs.getInt(4)).isEqualTo(1);
      assertThat(rs.getString(5)).isEqualTo("2024-03-05");
      assertThat(rs.getString(6)).isEqualTo("2024-03-05 01:02:03");
      // 둘째 행: 빈 값은 None → NULL(빈 문자열 TEXT 는 Tibero 에서 NULL 로 저장된다), BOOLEAN 빈 값은 거짓(0).
      assertThat(rs.next()).isTrue();
      assertThat(rs.getLong(1)).isEqualTo(2);
      assertThat(rs.getBigDecimal(2)).isNull();
      assertThat(rs.getString(3)).isNull();
      assertThat(rs.getInt(4)).isZero();
      assertThat(rs.getString(5)).isNull();
      assertThat(rs.getString(6)).isNull();
      // 셋째 행: 변환 불가 값은 전부 NULL.
      assertThat(rs.next()).isTrue();
      rs.getLong(1);
      assertThat(rs.wasNull()).isTrue();
      assertThat(rs.getBigDecimal(2)).isNull();
      assertThat(rs.getString(5)).isNull();
      assertThat(rs.getString(6)).isNull();
    }
  }
}
