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
import com.smartfirehub.dbexecutor.web.dto.QueryRequest;
import com.smartfirehub.dbexecutor.web.dto.QueryResponse;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Tibero 실서버 테스트(WD-77 Task 10) — 증분 SQL 의 커서 후보 조회와 {@code {{last_run_at}}} 리터럴 비교를 실서버에서 고정한다.
 *
 * <p>확인하는 사실: (1) 공개 동의어 {@code V$TRANSACTION} 은 일반 계정에서 풀리지 않지만 고정 테이블 {@code SYS._VT_TRANSACTION}
 * 은 RO 계정이 {@code /execute/query}(가드 포함)로 읽는다. (2) {@code START_TIME} 은 DATE(초 단위, 서버 시계 = {@code
 * SYSDATE})라 {@code FROM_TZ} 로 서버 오프셋을 붙여 instant 로 바꾼다. (3) {@code _UPDATED_AT} 은 시간대 없는 TIMESTAMP
 * 로 <b>서버 벽시계</b>를 담는다 — 책갈피(instant)는 서버에서 서버 오프셋으로 옮긴 식({@code CAST(TIMESTAMP '.. +00:00' AT TIME
 * ZONE TO_CHAR(SYSTIMESTAMP,'TZH:TZM') AS TIMESTAMP)})으로 비교해야 서버·세션 시간대와 무관하게 맞다. 시간대 있는 값을 열과 직접
 * 비교하면 세션 시간대로 어긋난다.
 *
 * <p>환경이 없으면 건너뛴다. 실행은 {@code scripts/tibero-test.sh}. 공유 서버 규칙은 {@link TiberoLiveAccount} 를 따른다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoLiveCursorTest {

  /** 가드가 유일하게 허용하는 커서 후보 문장(api {@code TiberoIncrementalCursor} 가 보내는 것과 같다 — 고정값 파일로 대조). */
  private static final String CANDIDATE_SQL =
      com.smartfirehub.dbexecutor.sql.TiberoSqlGuard.CURSOR_CANDIDATE_SQL;

  private static final DateTimeFormatter LITERAL =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

  private TiberoLiveAccount account;
  private String schema;
  private Connection owner;
  private ExecuteController controller;

  @BeforeAll
  void setUp() throws Exception {
    account = TiberoLiveAccount.open();
    schema = account.schema();
    owner = account.owner();
    StoreDescriptor descriptor = account.descriptor();
    TenantPools pools =
        new TenantPools(
            new HikariPoolFactory(4, StoreDescriptor::owner), StoreDescriptor::owner, 8);
    account.closeBeforeDrop(pools::closeAll);
    TenantPools roPools =
        new TenantPools(new HikariPoolFactory(2, StoreDescriptor::ro), StoreDescriptor::ro, 8);
    account.closeBeforeDrop(roPools::closeAll);
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
            new ExecutionTimeouts.Query(30));
    controller =
        new ExecuteController(
            descriptors,
            new SqlRunner(pools, timeouts, descriptors),
            new ProbeRunner(pools, timeouts, descriptors),
            new QueryRunner(roPools, timeouts, descriptors),
            new LoadRunner(pools, timeouts, descriptors),
            null,
            timeouts,
            new com.fasterxml.jackson.databind.ObjectMapper());
    String s = "\"" + schema + "\"";
    exec(
        owner,
        "CREATE TABLE "
            + s
            + ".\"T1\" (\"ID\" NUMBER, \"_UPDATED_AT\" TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL)");
  }

  @AfterAll
  void tearDown() throws Exception {
    if (account != null) {
      account.close();
    }
  }

  /** RO 계정이 가드를 거쳐 후보를 읽고, 다른 세션의 열린 트랜잭션 시작 시각 이하를 돌려준다. */
  @Test
  void live_candidate_roThroughGuard_isAtOrBeforeOpenTransactionStart() throws Exception {
    String s = "\"" + schema + "\"";
    owner.setAutoCommit(false);
    try {
      exec(owner, "INSERT INTO " + s + ".\"T1\" (\"ID\") VALUES (1)");
      LocalDateTime rowStamp = ownerTimestamp("SELECT \"_UPDATED_AT\" FROM " + s + ".\"T1\"");
      LocalDateTime candidate = candidate();
      System.out.println("LIVE candidate(open tx)=" + candidate + " rowUpdatedAt=" + rowStamp);
      // START_TIME 은 초 단위 DATE 라 후보는 열린 행의 _UPDATED_AT 이하(잘리며 이르게)다.
      assertThat(candidate).isBeforeOrEqualTo(rowStamp);
    } finally {
      owner.rollback();
      owner.setAutoCommit(true);
    }
    // 열린 트랜잭션이 없으면 후보는 서버 현재 시각(UTC)이다.
    LocalDateTime now =
        ownerTimestamp("SELECT CAST(SYSTIMESTAMP AT TIME ZONE '+00:00' AS TIMESTAMP) FROM DUAL");
    LocalDateTime idle = candidate();
    System.out.println("LIVE candidate(idle)=" + idle + " serverNowUtc=" + now);
    assertThat(Duration.between(idle, now).abs()).isLessThan(Duration.ofSeconds(10));
  }

  /**
   * api {@code LastRunAtPlaceholder} 가 Tibero 에 넣는 식(Ruling 13) — UTC instant 를 서버 벽시계(시간대 없음)로
   * 서버에서 변환한다.
   */
  private static String serverWallClock(String utc, String zoneExpr) {
    return "CAST(TIMESTAMP '" + utc + " +00:00' AT TIME ZONE " + zoneExpr + " AS TIMESTAMP)";
  }

  private static final String SERVER_OFFSET = "TO_CHAR(SYSTIMESTAMP, 'TZH:TZM')";

  /** 세션 시간대(서울·UTC·뉴욕)와 무관하게 변환 식이 맞게 비교되고, 시간대 있는 값을 열과 직접 비교하면 어긋난다. */
  @Test
  void live_expression_compare_isSessionTimeZoneIndependent() throws Exception {
    String s = "\"" + schema + "\"";
    exec(owner, "INSERT INTO " + s + ".\"T1\" (\"ID\") VALUES (10)");
    owner.commit();
    LocalDateTime nowUtc =
        ownerTimestamp("SELECT CAST(SYSTIMESTAMP AT TIME ZONE '+00:00' AS TIMESTAMP) FROM DUAL");
    String before = LITERAL.format(nowUtc.minusHours(1));
    String after = LITERAL.format(nowUtc.plusHours(1));
    for (String tz : new String[] {"Asia/Seoul", "UTC", "America/New_York"}) {
      exec(owner, "ALTER SESSION SET TIME_ZONE = '" + tz + "'");
      assertThat(count(s, "T1", serverWallClock(before, SERVER_OFFSET)))
          .as(tz + " 과거 책갈피")
          .isEqualTo(1);
      assertThat(count(s, "T1", serverWallClock(after, SERVER_OFFSET))).as(tz + " 미래 책갈피").isZero();
      assertThat(count(s, "T1", "TIMESTAMP '0001-01-01 00:00:00.000000'"))
          .as(tz + " 최소 리터럴")
          .isEqualTo(1);
    }
    // 시간대 있는 값을 열과 직접 비교하는 방식의 문제: 세션이 서울이면 열 값(서버 벽시계)을 서울 시각으로 읽어 0 행이 된다.
    exec(owner, "ALTER SESSION SET TIME_ZONE = 'Asia/Seoul'");
    int tstz = count(s, "T1", "TIMESTAMP '" + before + " +00:00'");
    System.out.println(
        "LIVE tstz literal under Asia/Seoul count="
            + tstz
            + " (server-wall-clock expression gives 1)");
    assertThat(tstz).isZero();
  }

  /**
   * 서버가 UTC 가 아닌 경우의 모의 — 서버 시계가 +09:00 이면 열은 +09:00 벽시계를 담는다. 같은 변환 식에 서버 오프셋만 +09:00 으로 바꿔
   * 넣으면(서버가 바뀌면 {@code TO_CHAR(SYSTIMESTAMP,'TZH:TZM')} 가 내는 값) 행이 맞게 선택된다. 이 서버는 UTC 이므로 오프셋을 직접
   * 지정해 확인한다.
   */
  @Test
  void live_expression_isCorrectForNonUtcServerClock() throws Exception {
    String s = "\"" + schema + "\"";
    exec(
        owner, "CREATE TABLE " + s + ".\"T2\" (\"ID\" NUMBER, \"_UPDATED_AT\" TIMESTAMP NOT NULL)");
    // +09:00 서버가 방금 쓴 행: 벽시계 = 지금(UTC) + 9시간.
    exec(
        owner,
        "INSERT INTO "
            + s
            + ".\"T2\" (\"ID\", \"_UPDATED_AT\") VALUES (1, CAST(SYSTIMESTAMP AT TIME ZONE '+09:00'"
            + " AS TIMESTAMP))");
    owner.commit();
    LocalDateTime nowUtc =
        ownerTimestamp("SELECT CAST(SYSTIMESTAMP AT TIME ZONE '+00:00' AS TIMESTAMP) FROM DUAL");
    // 30분 전 책갈피(instant) 이후에 쓴 행이므로 선택되어야 하고, 30분 뒤 책갈피 이후는 아니어야 한다.
    assertThat(count(s, "T2", serverWallClock(LITERAL.format(nowUtc.minusMinutes(30)), "'+09:00'")))
        .isEqualTo(1);
    assertThat(count(s, "T2", serverWallClock(LITERAL.format(nowUtc.plusMinutes(30)), "'+09:00'")))
        .isZero();
    // 서버 시간대를 UTC 로 가정한 옛 리터럴(UTC 벽시계)이면 +09:00 서버의 행을 30분 뒤 책갈피에도 포함시켜 중복 읽기를 만든다.
    assertThat(count(s, "T2", "TIMESTAMP '" + LITERAL.format(nowUtc.plusMinutes(30)) + "'"))
        .as("UTC 가정 리터럴은 +09:00 서버에서 어긋난다")
        .isEqualTo(1);
  }

  private int count(String quotedSchema, String table, String expression) throws Exception {
    try (Statement st = owner.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT COUNT(*) FROM "
                    + quotedSchema
                    + ".\""
                    + table
                    + "\" WHERE \"_UPDATED_AT\" >= "
                    + expression)) {
      rs.next();
      return rs.getInt(1);
    }
  }

  /** RO 계정으로 후보 문장을 /execute/query 에 실제로 보낸다. */
  private LocalDateTime candidate() {
    QueryResponse r =
        LiveQuery.run(
            controller, new QueryRequest(account.tenantId(), CANDIDATE_SQL, 1, true, null, null));
    assertThat(r.success()).as(String.valueOf(r.error())).isTrue();
    assertThat(r.rows()).hasSize(1);
    String text = String.valueOf(r.rows().get(0).get("candidate"));
    return LocalDateTime.parse(text.replace(' ', 'T'));
  }

  private LocalDateTime ownerTimestamp(String sql) throws Exception {
    try (Statement st = owner.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getObject(1, LocalDateTime.class);
    }
  }
}
