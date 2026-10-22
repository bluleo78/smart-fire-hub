package com.smartfirehub.dbexecutor.live;

import static com.smartfirehub.dbexecutor.live.TiberoLiveAccount.exec;
import static com.smartfirehub.dbexecutor.live.TiberoLiveAccount.execIgnore;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dbexecutor.config.DbExecutorProperties;
import com.smartfirehub.dbexecutor.config.ExecutionTimeouts;
import com.smartfirehub.dbexecutor.sql.ProbeRunner;
import com.smartfirehub.dbexecutor.sql.SqlRunner;
import com.smartfirehub.dbexecutor.store.HikariPoolFactory;
import com.smartfirehub.dbexecutor.store.StoreDescriptor;
import com.smartfirehub.dbexecutor.store.StoreDescriptorClient;
import com.smartfirehub.dbexecutor.store.StoreUnavailableException;
import com.smartfirehub.dbexecutor.store.TenantPools;
import com.smartfirehub.dbexecutor.web.ExecuteController;
import com.smartfirehub.dbexecutor.web.dto.ProbeColumn;
import com.smartfirehub.dbexecutor.web.dto.ProbeRequest;
import com.smartfirehub.dbexecutor.web.dto.ProbeResponse;
import com.smartfirehub.dbexecutor.web.dto.SqlExecuteRequest;
import com.smartfirehub.dbexecutor.web.dto.SqlExecuteResponse;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Tibero 실서버 테스트(WD-77 Task 5) — {@code /execute/sql}·{@code /execute/probe} 를 실제 서버에 돌린다.
 *
 * <p>{@code TIBERO_TEST_HOST/PORT/SID/DBA_USER/DBA_PASSWORD}(선택 {@code TIBERO_TEST_TABLESPACE})가
 * 없거나 JDBC 드라이버가 없으면 클래스째 건너뛴다. 실행은 {@code scripts/tibero-test.sh}. 접속 값은 어디에도 출력하지 않는다.
 *
 * <p><b>공유 서버 규칙</b>: api 계약 테스트({@code TiberoTestEnvironment})와 같이 예약 대역(900,000,000 + 무작위) 테넌트 id
 * 로 전용 소유 계정 {@code GENIA_T<id>} 하나만 만들고, 같은 이름이 이미 있으면 거절한다(남의 계정은 절대 지우지 않는다). 끝나면 이 테스트가 만든 계정만
 * {@code DROP USER … CASCADE} 하고 남은 계정 수를 확인한다.
 *
 * <p>적재 문장은 api {@code TiberoPipelineSql}(커밋 ce255754)의 실제 출력을 고정 문자열로 옮겼다 — 스키마 {@code GENIA_T5} 와
 * 사용자 SQL 자리 {@code __USER_SQL__} 로 생성한 결과이며, 실행 때는 스키마 토큰과 사용자 SQL 자리만 바꾼다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoLiveSqlTest {

  // ---- api TiberoPipelineSql 실제 출력(스키마 GENIA_T5, 테이블 orders, 컬럼 id_key·amount·name, 키 id_key)
  // ----

  /** {@code TiberoPipelineSql.deleteAll("GENIA_T5", "orders")}. */
  private static final String DELETE_T = "DELETE FROM \"GENIA_T5\".\"ORDERS\"";

  /** {@code TiberoNames.sequenceName("GENIA_T5", "orders")}. */
  private static final String SEQ = "SQ_E4139FAB67";

  /**
   * {@code TiberoPipelineSql.insertSelect("GENIA_T5", "orders", [id_key, amount, name],
   * "__USER_SQL__")}.
   */
  private static final String INSERT_T =
      "INSERT INTO \"GENIA_T5\".\"ORDERS\" (\"ID\", \"ID_KEY\", \"AMOUNT\", \"NAME\","
          + " \"_UPDATED_AT\") SELECT \"GENIA_T5\".\"SQ_E4139FAB67\".NEXTVAL, src.\"ID_KEY\","
          + " src.\"AMOUNT\", src.\"NAME\", SYSTIMESTAMP FROM (\n"
          + "__USER_SQL__\n"
          + ") src";

  /**
   * {@code TiberoPipelineSql.mergeSelect("GENIA_T5", "orders", [id_key, amount, name], [id_key],
   * "__USER_SQL__")}.
   */
  private static final String MERGE_T =
      "MERGE INTO \"GENIA_T5\".\"ORDERS\" t USING (\n"
          + "__USER_SQL__\n"
          + ") s ON (t.\"ID_KEY\" = s.\"ID_KEY\") WHEN MATCHED THEN UPDATE SET t.\"AMOUNT\" ="
          + " s.\"AMOUNT\", t.\"NAME\" = s.\"NAME\", t.\"_UPDATED_AT\" = SYSTIMESTAMP WHERE"
          + " (DECODE(t.\"AMOUNT\", s.\"AMOUNT\", 0, 1) = 1 OR DECODE(t.\"NAME\", s.\"NAME\", 0, 1)"
          + " = 1) WHEN NOT MATCHED THEN INSERT (\"ID\", \"ID_KEY\", \"AMOUNT\", \"NAME\","
          + " \"_UPDATED_AT\") VALUES (\"GENIA_T5\".\"SQ_E4139FAB67\".NEXTVAL, s.\"ID_KEY\","
          + " s.\"AMOUNT\", s.\"NAME\", SYSTIMESTAMP)";

  private TiberoLiveAccount account;
  private long tenantId;
  private String schema;
  private Connection owner;
  private TenantPools pools;
  private StoreDescriptor descriptor;
  private ExecuteController controller;

  /** 느린 본문 생성용: 1초에 셀 수 있는 카티션 행 수(BeforeAll 에서 실측). */
  private double rowsPerSecond;

  @BeforeAll
  void setUp() throws Exception {
    // 전용 계정 생성(환경·드라이버가 없으면 건너뜀)과 정리 규칙은 TiberoLiveAccount 가 맡는다.
    account = TiberoLiveAccount.open();
    tenantId = account.tenantId();
    schema = account.schema();
    owner = account.owner();
    descriptor = account.descriptor();
    pools = new TenantPools(new HikariPoolFactory(4), 8);
    // 풀이 계정 세션을 잡고 있으면 DROP USER 가 거절되므로 먼저 닫는다.
    account.closeBeforeDrop(pools::closeAll);
    controller = controller(descriptor, new ExecutionTimeouts.Sql(50, 55));
    rowsPerSecond = calibrate();
    System.out.printf("LIVE calibrate rowsPerSecond=%.0f%n", rowsPerSecond);
  }

  @AfterAll
  void tearDown() throws Exception {
    if (account != null) {
      account.close();
    }
  }

  /** 기술자 클라이언트를 테스트 더블로 바꾼 컨트롤러(HTTP 만 빼고 실제 경로). */
  private ExecuteController controller(StoreDescriptor descriptor, ExecutionTimeouts.Sql sql) {
    StoreDescriptorClient descriptors =
        new StoreDescriptorClient(
            new DbExecutorProperties(
                "live-token", "http://127.0.0.1:9", 60, new DbExecutorProperties.Pool(8, 4)),
            new ObjectMapper()) {
          @Override
          public StoreDescriptor get(long id, Duration budget) {
            if (id != descriptor.tenantId()) {
              throw new StoreUnavailableException("404");
            }
            return descriptor;
          }
        };
    ExecutionTimeouts timeouts = new ExecutionTimeouts(sql, new ExecutionTimeouts.Probe(30));
    return new ExecuteController(
        descriptors,
        new SqlRunner(pools, timeouts, descriptors),
        new ProbeRunner(pools, timeouts, descriptors),
        timeouts);
  }

  /** 매 테스트마다 api createTable 과 같은 모양의 출력 테이블·시퀀스를 새로 만든다. */
  @BeforeEach
  void recreateTable() throws SQLException {
    String s = "\"" + schema + "\"";
    execIgnore(owner, "DROP TABLE " + s + ".\"ORDERS\"");
    execIgnore(owner, "DROP SEQUENCE " + s + ".\"" + SEQ + "\"");
    exec(owner, "CREATE SEQUENCE " + s + ".\"" + SEQ + "\" START WITH 1 INCREMENT BY 1 NOCACHE");
    exec(
        owner,
        "CREATE TABLE "
            + s
            + ".\"ORDERS\" (\"ID\" NUMBER(19) PRIMARY KEY, \"IMPORT_ID\" NUMBER(19), \"ID_KEY\""
            + " NUMBER(19), \"AMOUNT\" NUMBER(18,6), \"NAME\" VARCHAR2(100 CHAR), \"CREATED_AT\""
            + " TIMESTAMP DEFAULT SYSTIMESTAMP, \"_UPDATED_AT\" TIMESTAMP DEFAULT SYSTIMESTAMP NOT"
            + " NULL)");
    exec(
        owner,
        "CREATE UNIQUE INDEX " + s + ".\"UX_ORDERS_KEY\" ON " + s + ".\"ORDERS\" (\"ID_KEY\")");
  }

  // ------------------------------------------------------------------ 테스트

  @Test
  void live_replace_append_merge() throws Exception {
    // REPLACE 1회차: 3행
    ok(
        replace(
            "SELECT 1 AS id_key, 10.5 AS amount, 'a' AS name FROM DUAL UNION ALL SELECT 2, 20, 'b' FROM DUAL UNION ALL SELECT 3, 30, 'c' FROM DUAL"));
    assertThat(keys()).containsExactly(1L, 2L, 3L);
    // REPLACE 2회차: 마지막 결과만 남는다
    SqlExecuteResponse r2 =
        ok(
            replace(
                "SELECT 1 AS id_key, 11 AS amount, 'a2' AS name FROM DUAL UNION ALL SELECT 2, 20, 'b' FROM DUAL"));
    assertThat(r2.rowCount()).isEqualTo(2);
    assertThat(keys()).containsExactly(1L, 2L);
    // APPEND: 누적
    ok(append("SELECT 3 AS id_key, 30 AS amount, CAST(NULL AS VARCHAR2(100)) AS name FROM DUAL"));
    assertThat(keys()).containsExactly(1L, 2L, 3L);

    Map<Long, Timestamp> before = updatedAt();
    Thread.sleep(300);
    // MERGE: 1(그대로) 2(금액 변경) 3(그대로, NULL 이름 — DECODE 의 NULL=NULL) 4(새 행)
    SqlExecuteResponse m =
        ok(
            merge(
                "SELECT 1 AS id_key, 11 AS amount, 'a2' AS name FROM DUAL UNION ALL SELECT 2, 21,"
                    + " 'b' FROM DUAL UNION ALL SELECT 3, 30, CAST(NULL AS VARCHAR2(100)) FROM DUAL"
                    + " UNION ALL SELECT 4, 40, 'd' FROM DUAL"));
    System.out.println("LIVE merge row_count=" + m.rowCount());
    Map<Long, Timestamp> after = updatedAt();
    assertThat(after.keySet()).containsExactly(1L, 2L, 3L, 4L);
    assertThat(after.get(1L)).isEqualTo(before.get(1L));
    assertThat(after.get(3L)).isEqualTo(before.get(3L));
    assertThat(after.get(2L)).isAfter(before.get(2L));
    assertThat(amount(2L)).isEqualByComparingTo("21");
    assertThat(m.rowCount()).isEqualTo(2);
  }

  @Test
  void live_concurrentReplace_noDuplicates() throws Exception {
    double slowSeconds = 2.5;
    String body =
        "SELECT k AS id_key, k * 10 AS amount, 'c' AS name FROM (SELECT LEVEL k FROM DUAL CONNECT BY"
            + " LEVEL <= 3) WHERE ("
            + slowCount(slowSeconds)
            + ") > 0";
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<long[]>> futures = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  long t0 = System.nanoTime();
                  SqlExecuteResponse r = replace(body);
                  long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
                  assertThat(r.success()).as(String.valueOf(r.error())).isTrue();
                  return new long[] {ms};
                }));
      }
      start.countDown();
      long slower = 0;
      long faster = Long.MAX_VALUE;
      for (Future<long[]> f : futures) {
        long ms = f.get(120, TimeUnit.SECONDS)[0];
        slower = Math.max(slower, ms);
        faster = Math.min(faster, ms);
      }
      System.out.println("LIVE concurrent replace ms faster=" + faster + " slower=" + slower);
      // 두 실행이 겹쳤다(뒤 실행이 잠금을 기다림) — 늦은 쪽이 대략 두 배 걸린다.
      assertThat(slower).isGreaterThan((long) (faster * 1.5));
    } finally {
      pool.shutdownNow();
    }
    assertThat(keys()).containsExactly(1L, 2L, 3L);
    assertThat(count()).isEqualTo(3);
  }

  @Test
  void live_timeout_rollsBackKeepsOldRows() throws Exception {
    ok(
        replace(
            "SELECT 1 AS id_key, 1 AS amount, 'old' AS name FROM DUAL UNION ALL SELECT 2, 2, 'old' FROM DUAL"));
    List<Long> idsBefore = ids();

    // 같은 풀을 쓰되 전체 제한만 2초로 줄인 실행기(문장 제한 50초는 그대로 — 전체 기한 감시 경로를 탄다).
    ExecuteController shortTimeout = controller(descriptor, new ExecutionTimeouts.Sql(50, 2));
    String slow =
        "SELECT 9 AS id_key, 9 AS amount, 'new' AS name FROM DUAL WHERE ("
            + slowCount(30)
            + ") > 0";
    long t0 = System.nanoTime();
    SqlExecuteResponse r =
        shortTimeout.executeSql(
            new SqlExecuteRequest(tenantId, s(INSERT_T, slow), List.of(s(DELETE_T, null))));
    long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
    System.out.println(
        "LIVE timeout elapsedMs="
            + ms
            + " error="
            + r.error()
            + " log="
            + r.executionLog().replace('\n', '|'));

    assertThat(r.success()).isFalse();
    assertThat(r.error()).isEqualTo("실행 시간이 2초를 넘어 취소했습니다.");
    assertThat(ms).isLessThan(15_000);
    assertThat(ids()).isEqualTo(idsBefore);
    assertThat(keys()).containsExactly(1L, 2L);
  }

  @Test
  void live_rejects_ddl_plsql_multi_dangerous() throws Exception {
    ok(replace("SELECT 1 AS id_key, 1 AS amount, 'keep' AS name FROM DUAL"));
    String t = "\"" + schema + "\".\"ORDERS\"";
    String[] bad = {
      "DROP TABLE " + t,
      "TRUNCATE TABLE " + t,
      "BEGIN NULL; END;",
      "BEGIN EXECUTE IMMEDIATE 'DROP TABLE ORDERS'; END;",
      "DELETE FROM " + t + "; DROP TABLE " + t,
      "SELECT 1 FROM DUAL; SELECT 2 FROM DUAL",
      "SELECT DBMS_XMLGEN.getxml('select 1 from dual') AS x FROM DUAL",
      "SELECT SYS.DBMS_RANDOM.VALUE FROM DUAL",
      "LOCK TABLE " + t + " IN EXCLUSIVE MODE",
      "COMMIT",
    };
    for (String sql : bad) {
      SqlExecuteResponse r = controller.executeSql(new SqlExecuteRequest(tenantId, sql, List.of()));
      assertThat(r.success()).as(sql).isFalse();
      assertThat(r.dbError()).as(sql).isNull();
      assertThat(r.error()).as(sql).isNotBlank();
    }
    // 다른 스키마의 선행 문장도 실행 전에 거부된다.
    SqlExecuteResponse pre =
        controller.executeSql(
            new SqlExecuteRequest(
                tenantId,
                s(INSERT_T, "SELECT 5 AS id_key, 5 AS amount, 'x' AS name FROM DUAL"),
                List.of("DELETE FROM \"GENIA_T5\".\"ORDERS\"")));
    assertThat(pre.success()).isFalse();
    assertThat(pre.error()).isEqualTo("허용되지 않은 선행 문장입니다.");
    // 테이블과 행은 그대로다.
    assertThat(keys()).containsExactly(1L);
  }

  @Test
  void live_probe_columns() {
    ProbeResponse r =
        controller.probe(new ProbeRequest(tenantId, "SELECT 1 AS a, 'x' b, SYSDATE d FROM DUAL"));
    assertThat(r.success()).as(String.valueOf(r.error())).isTrue();
    // 탐지 이름은 바꾸지 않고 온다(F2) — 따옴표 없는 별칭은 Tibero 가 대문자로 접는다.
    assertThat(r.columns()).extracting(ProbeColumn::name).containsExactly("A", "B", "D");
    for (ProbeColumn c : r.columns()) {
      System.out.println("LIVE probe column " + c);
      assertThat(c.jdbcType()).isNotZero();
      assertThat(c.typeName()).isNotBlank();
    }
    // 출력 테이블 컬럼도 탐지된다(사용자 SQL 처럼 스키마 한정, 행은 읽지 않음).
    ProbeResponse t =
        controller.probe(
            new ProbeRequest(
                tenantId, "SELECT id_key, amount, name FROM " + schema.toLowerCase() + ".orders"));
    assertThat(t.success()).as(String.valueOf(t.error())).isTrue();
    assertThat(t.columns())
        .extracting(ProbeColumn::name)
        .containsExactly("ID_KEY", "AMOUNT", "NAME");
    // 따옴표 소문자·대소문자 섞인 별칭은 쓴 그대로 온다 — api 가 따옴표 없는 별칭(대문자)과 가를 수 있다.
    ProbeResponse q =
        controller.probe(
            new ProbeRequest(
                tenantId, "SELECT 1 AS \"amount\", 2 AS amount2, 3 AS \"Mixed\" FROM DUAL"));
    assertThat(q.success()).as(String.valueOf(q.error())).isTrue();
    assertThat(q.columns())
        .extracting(ProbeColumn::name)
        .containsExactly("amount", "AMOUNT2", "Mixed");
  }

  @Test
  void live_nlsDateFormat() {
    SqlExecuteResponse r =
        controller.executeSql(
            new SqlExecuteRequest(
                tenantId,
                "SELECT TO_CHAR(DATE '2026-01-02') AS d, TO_CHAR(TIMESTAMP '2026-01-02"
                    + " 03:04:05.123456') AS ts, TO_CHAR(1.5) AS n FROM DUAL",
                List.of()));
    assertThat(r.success()).as(String.valueOf(r.error())).isTrue();
    assertThat(r.rows()).hasSize(1);
    Map<String, Object> row = r.rows().get(0);
    assertThat(row.get("d")).isEqualTo("2026-01-02");
    assertThat(row.get("ts")).isEqualTo("2026-01-02 03:04:05.123456");
    assertThat(row.get("n")).isEqualTo("1.5");
  }

  /** 실서버 메타로 값 변환 규칙이 맞게 동작하는지(고정값 규칙의 실제 입력 확인). */
  @Test
  void live_selectRows_valueConversion() {
    SqlExecuteResponse r =
        controller.executeSql(
            new SqlExecuteRequest(
                tenantId,
                "SELECT 1.5 AS x, CAST(2.5 AS NUMBER(10,2)) AS y, CAST(7 AS NUMBER(19)) AS z,"
                    + " COUNT(*) AS c, DATE '2026-01-02' AS dt, TIMESTAMP '2026-01-02"
                    + " 03:04:05.123456' AS t, \"Mixed\" FROM (SELECT 1 AS \"Mixed\" FROM DUAL)"
                    + " GROUP BY \"Mixed\"",
                List.of()));
    assertThat(r.success()).as(String.valueOf(r.error())).isTrue();
    System.out.println("LIVE select columns=" + r.columns() + " row=" + r.rows().get(0));
    ProbeResponse p =
        controller.probe(
            new ProbeRequest(
                tenantId,
                "SELECT 1.5 AS x, CAST(2.5 AS NUMBER(10,2)) AS y, CAST(7 AS NUMBER(19)) AS z,"
                    + " COUNT(*) AS c, DATE '2026-01-02' AS dt FROM DUAL"));
    p.columns().forEach(c -> System.out.println("LIVE value-meta " + c));
    assertThat(r.columns()).containsExactly("x", "y", "z", "c", "dt", "t", "Mixed");
    Map<String, Object> row = r.rows().get(0);
    assertThat(row.get("x")).isEqualTo(new BigDecimal("1.5"));
    assertThat(row.get("y")).isEqualTo(new BigDecimal("2.50"));
    assertThat(row.get("z")).isEqualTo(7L);
    assertThat(row.get("c")).isEqualTo(1L);
    assertThat(row.get("dt")).isEqualTo("2026-01-02T00:00:00");
    assertThat(row.get("t")).isEqualTo("2026-01-02T03:04:05.123456");
    assertThat(row.get("Mixed")).isEqualTo(1L);
  }

  @Test
  void live_mergeSourceDuplicateKey_code() throws Exception {
    ok(replace("SELECT 1 AS id_key, 1 AS amount, 'base' AS name FROM DUAL"));
    // 대상에 있는 키(1)가 원본에 두 번 — Oracle ORA-30926 에 해당하는 경우. 둘 다 대상과 값이 달라 DECODE 조건에 걸러지지 않는다.
    SqlExecuteResponse r =
        merge(
            "SELECT 1 AS id_key, 100 AS amount, 'x' AS name FROM DUAL UNION ALL SELECT 1, 200, 'y' FROM DUAL");
    System.out.println(
        "LIVE merge_dup_matched success="
            + r.success()
            + " code="
            + (r.dbError() == null ? null : r.dbError().code())
            + " message="
            + (r.dbError() == null ? r.error() : r.dbError().message()));
    assertThat(r.success()).isFalse();
    assertThat(r.dbError()).isNotNull();
    assertThat(r.dbError().code()).isNegative();
    assertThat(keys()).containsExactly(1L);
    assertThat(amount(1L)).isEqualByComparingTo("1");

    // 참고 측정: 대상에 없는 키(99)가 원본에 두 번 — 둘 다 INSERT 로 가 유일 인덱스에 걸린다.
    SqlExecuteResponse n =
        merge(
            "SELECT 99 AS id_key, 1 AS amount, 'p' AS name FROM DUAL UNION ALL SELECT 99, 2, 'q' FROM DUAL");
    System.out.println(
        "LIVE merge_dup_unmatched success="
            + n.success()
            + " code="
            + (n.dbError() == null ? null : n.dbError().code())
            + " message="
            + (n.dbError() == null ? n.error() : n.dbError().message()));
    assertThat(n.success()).isFalse();
    assertThat(keys()).containsExactly(1L);
  }

  /**
   * Tibero 서버 어휘 사실 고정(WD-77 최종 리뷰 FF2) — 검증기·가드의 보안 논증이 기대는 전제를 실서버로 확인한다. 가드를 거치지 않고 소유 계정 커넥션에
   * 실행기({@code SqlRunner})와 같은 {@link Statement} 로 바로 보낸다(측정 대상은 드라이버+서버의 해석이다).
   *
   * <ul>
   *   <li>백슬래시는 문자열에서 특수 문자가 아니다 — {@code 'a\'} 는 {@code a\} 다. 이스케이프였다면 문자열이 닫히지 않아 오류가 났을 것이다. 이
   *       전제가 깨지면 검증기(JSqlParser·TiberoSqlLexer)가 문자열로 보는 구간을 Tibero 가 코드로 실행한다.
   *   <li>대체 따옴표 {@code q'[..]'} 를 지원한다 — db-executor 가드 사본이 이 구간을 문자열로 가리는 전제(api 는 아예 거부).
   *   <li>블록 주석은 중첩되지 않는다, {@code \r} 은 {@code --} 주석을 끝낸다 — 어긋나도 안전한 방향이지만 함께 고정한다.
   *   <li>따옴표 없는 비ASCII 식별자를 허용한다 — 가드가 비ASCII 글자를 식별자 문자로 보는 전제(FF3).
   * </ul>
   */
  @Test
  void live_lexicalFacts() throws Exception {
    String backslash = scalarOrError("SELECT 'a\\' AS x FROM DUAL");
    System.out.println("LIVE lexical backslash value=" + backslash);
    assertThat(backslash).isEqualTo("a\\").hasSize(2);
    // 백슬래시 뒤의 '' 도 일반 이스케이프(따옴표 한 개)로 읽힌다.
    assertThat(scalar("SELECT 'a\\''b' AS x FROM DUAL")).isEqualTo("a\\'b");

    String qQuote = scalarOrError("SELECT q'[a'b]' AS y FROM DUAL");
    System.out.println("LIVE lexical q-quote value=" + qQuote);
    assertThat(qQuote).isEqualTo("a'b");

    String nested = scalarOrError("SELECT '1' /* /* */ AS z FROM DUAL");
    System.out.println("LIVE lexical non-nested block comment value=" + nested);
    assertThat(nested).isEqualTo("1");

    String cr = scalarOrError("SELECT '1' AS w -- c\r FROM DUAL");
    System.out.println("LIVE lexical CR ends line comment value=" + cr);
    assertThat(cr).isEqualTo("1");

    String nonAscii = scalarOrError("SELECT 한글컬럼 FROM (SELECT '1' AS 한글컬럼 FROM DUAL)");
    System.out.println("LIVE lexical non-ASCII identifier value=" + nonAscii);
    assertThat(nonAscii).isEqualTo("1");
  }

  // ------------------------------------------------------------------ 도우미

  /** 소유 계정 커넥션으로 한 값짜리 조회를 실행해 첫 컬럼 문자열을 돌려준다. */
  private String scalar(String sql) throws SQLException {
    try (Statement st = owner.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      assertThat(rs.next()).as(sql).isTrue();
      return rs.getString(1);
    }
  }

  /** {@link #scalar} 와 같되, 오류면 던지지 않고 「ERROR code=…」 로 돌려준다 — 관측값을 먼저 출력하고 단언하기 위해. */
  private String scalarOrError(String sql) {
    try {
      return scalar(sql);
    } catch (SQLException e) {
      return "ERROR code=" + e.getErrorCode() + " " + e.getMessage();
    }
  }

  private SqlExecuteResponse replace(String userSql) {
    return controller.executeSql(
        new SqlExecuteRequest(tenantId, s(INSERT_T, userSql), List.of(s(DELETE_T, null))));
  }

  private SqlExecuteResponse append(String userSql) {
    return controller.executeSql(new SqlExecuteRequest(tenantId, s(INSERT_T, userSql), List.of()));
  }

  private SqlExecuteResponse merge(String userSql) {
    return controller.executeSql(new SqlExecuteRequest(tenantId, s(MERGE_T, userSql), List.of()));
  }

  private static SqlExecuteResponse ok(SqlExecuteResponse r) {
    assertThat(r.success()).as(r.error() + " / " + r.executionLog()).isTrue();
    return r;
  }

  /** 고정 문자열의 스키마 토큰과 사용자 SQL 자리만 바꾼다. */
  private String s(String template, String userSql) {
    String out = template.replace("\"GENIA_T5\"", "\"" + schema + "\"");
    return userSql == null ? out : out.replace("__USER_SQL__", userSql);
  }

  /** 약 seconds 초 걸리는 CPU 위주 카운트(작은 생성기 두 개의 카티션 곱 — 큰 CONNECT BY 하나보다 메모리를 덜 쓴다). */
  private String slowCount(double seconds) {
    long a = Math.max(1, Math.min(2_000_000L, Math.round(rowsPerSecond * seconds / 1000)));
    return "SELECT COUNT(*) FROM (SELECT LEVEL l FROM DUAL CONNECT BY LEVEL <= "
        + a
        + ") ga, (SELECT LEVEL l FROM DUAL CONNECT BY LEVEL <= 1000) gb";
  }

  /** 카티션 카운트 속도를 실측한다(서버 부하에 따라 느린 본문 크기를 맞추기 위해). */
  private double calibrate() throws SQLException {
    long rows = 2_000L * 1_000L;
    long t0 = System.nanoTime();
    try (Statement st = owner.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT COUNT(*) FROM (SELECT LEVEL l FROM DUAL CONNECT BY LEVEL <= 2000) ga,"
                    + " (SELECT LEVEL l FROM DUAL CONNECT BY LEVEL <= 1000) gb")) {
      rs.next();
    }
    double secs = Math.max(0.001, (System.nanoTime() - t0) / 1e9);
    return rows / secs;
  }

  private List<Long> keys() throws SQLException {
    List<Long> out = new ArrayList<>();
    try (Statement st = owner.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT \"ID_KEY\" FROM \"" + schema + "\".\"ORDERS\" ORDER BY \"ID_KEY\"")) {
      while (rs.next()) {
        out.add(rs.getLong(1));
      }
    }
    return out;
  }

  private List<Long> ids() throws SQLException {
    List<Long> out = new ArrayList<>();
    try (Statement st = owner.createStatement();
        ResultSet rs =
            st.executeQuery("SELECT \"ID\" FROM \"" + schema + "\".\"ORDERS\" ORDER BY \"ID\"")) {
      while (rs.next()) {
        out.add(rs.getLong(1));
      }
    }
    return out;
  }

  private long count() throws SQLException {
    try (Statement st = owner.createStatement();
        ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM \"" + schema + "\".\"ORDERS\"")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private BigDecimal amount(long key) throws SQLException {
    try (PreparedStatement ps =
        owner.prepareStatement(
            "SELECT \"AMOUNT\" FROM \"" + schema + "\".\"ORDERS\" WHERE \"ID_KEY\" = ?")) {
      ps.setLong(1, key);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getBigDecimal(1);
      }
    }
  }

  private Map<Long, Timestamp> updatedAt() throws SQLException {
    Map<Long, Timestamp> out = new LinkedHashMap<>();
    try (Statement st = owner.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT \"ID_KEY\", \"_UPDATED_AT\" FROM \""
                    + schema
                    + "\".\"ORDERS\" ORDER BY \"ID_KEY\"")) {
      while (rs.next()) {
        out.put(rs.getLong(1), rs.getTimestamp(2));
      }
    }
    return out;
  }
}
