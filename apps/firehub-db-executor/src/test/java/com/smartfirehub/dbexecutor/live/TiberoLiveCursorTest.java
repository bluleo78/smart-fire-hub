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
 * SYSDATE})라 {@code FROM_TZ} 로 서버 지역 시간대({@code TZR})를 붙여 instant 로 바꾼다. (3) {@code _UPDATED_AT} 은
 * 시간대 없는 TIMESTAMP 로 <b>서버 벽시계</b>를 담는다 — 책갈피(instant)는 서버에서 서버 지역 시간대로 옮긴 식({@code CAST(TIMESTAMP
 * '.. +00:00' AT TIME ZONE TO_CHAR(SYSTIMESTAMP,'TZR') AS TIMESTAMP)})으로 비교해야 서버·세션 시간대와 무관하게 맞다.
 * 시간대 있는 값을 열과 직접 비교하면 세션 시간대로 어긋난다. (4) 지역 이름으로 옮기면 서머타임 전환을 가로지른 책갈피도 맞다(WD-234 — 예전 현재 오프셋
 * {@code TZH:TZM} 식은 봄 전환에서 행을 놓쳤다).
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
            new ExecutionTimeouts.Sql(50, 55, 1800),
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

  /** api {@code TiberoTimestamps.SERVER_ZONE_SQL} 과 같은 서버 지역 시간대 식(WD-234). */
  private static final String SERVER_ZONE = "TO_CHAR(SYSTIMESTAMP, 'TZR')";

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
      assertThat(count(s, "T1", serverWallClock(before, SERVER_ZONE)))
          .as(tz + " 과거 책갈피")
          .isEqualTo(1);
      assertThat(count(s, "T1", serverWallClock(after, SERVER_ZONE))).as(tz + " 미래 책갈피").isZero();
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
   * 서버가 UTC 가 아닌 경우의 모의 — 서버 시계가 +09:00 이면 열은 +09:00 벽시계를 담는다. 같은 변환 식에 서버 시간대만 +09:00 으로 바꿔
   * 넣으면(TZR 로 오프셋 문자열을 주는 서버에서 {@code TO_CHAR(SYSTIMESTAMP,'TZR')} 가 내는 값) 행이 맞게 선택된다. 이 서버는 UTC
   * 이므로 오프셋을 직접 지정해 확인한다.
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

  /**
   * 서머타임 서버의 모의(WD-234) — 개발 서버는 UTC 라 서버 시간대를 바꿀 수 없으므로, 열에 뉴욕 벽시계를 직접 넣고 변환 식의 시간대 자리에 지역 이름
   * 리터럴({@code 'America/New_York'}, 그런 서버에서 {@code TO_CHAR(SYSTIMESTAMP,'TZR')} 가 내는 값)을 넣는다.
   * 2026-03-08 02:00 EST 에 03:00 EDT 로 넘어간다.
   *
   * <ul>
   *   <li>행 1: 벽시계 01:45(EST = 06:45Z), 행 2: 03:10(EDT = 07:10Z).
   *   <li>전환 전 책갈피 06:30Z(01:30 EST): 지역 이름으로 옮기면 01:30 → 두 행 모두 읽는다. 예전 현재 오프셋 식은 전환 뒤 실행 때 -04:00
   *       으로 옮겨 02:30 → 행 1 을 놓친다.
   *   <li>전환 뒤 책갈피 07:00Z(03:00 EDT): 03:00 → 행 2 만 읽는다.
   * </ul>
   *
   * 세션 시간대(UTC·서울·뉴욕)와 무관하게 같은 행을 고른다. 후보 쪽과 같은 모양({@code FROM_TZ(CAST(DATE AS TIMESTAMP), 지역)})도
   * 전환 전후 오프셋을 맞게 쓴다.
   */
  @Test
  void live_regionZone_bookmarkAcrossSpringForward() throws Exception {
    String s = "\"" + schema + "\"";
    exec(
        owner, "CREATE TABLE " + s + ".\"T4\" (\"ID\" NUMBER, \"_UPDATED_AT\" TIMESTAMP NOT NULL)");
    exec(owner, "INSERT INTO " + s + ".\"T4\" VALUES (1, TIMESTAMP '2026-03-08 01:45:00.000000')");
    exec(owner, "INSERT INTO " + s + ".\"T4\" VALUES (2, TIMESTAMP '2026-03-08 03:10:00.000000')");
    owner.commit();
    String ny = "'America/New_York'";
    for (String tz : new String[] {"UTC", "Asia/Seoul", "America/New_York"}) {
      exec(owner, "ALTER SESSION SET TIME_ZONE = '" + tz + "'");
      assertThat(count(s, "T4", serverWallClock("2026-03-08 06:30:00.000000", ny)))
          .as(tz + " 전환 전 책갈피 — 지역 이름")
          .isEqualTo(2);
      assertThat(count(s, "T4", serverWallClock("2026-03-08 06:30:00.000000", "'-04:00'")))
          .as(tz + " 전환 전 책갈피 — 예전 현재 오프셋(전환 뒤 -04:00)은 행 1 을 놓친다")
          .isEqualTo(1);
      assertThat(count(s, "T4", serverWallClock("2026-03-08 07:00:00.000000", ny)))
          .as(tz + " 전환 뒤 책갈피 — 지역 이름")
          .isEqualTo(1);
    }
    exec(owner, "ALTER SESSION SET TIME_ZONE = 'UTC'");
    // 후보 식 모양: 시간대 없는 DATE(START_TIME)를 지역 이름으로 해석 — 전환 전 EST(-5), 전환 뒤 EDT(-4).
    assertThat(
            ownerTimestamp(
                "SELECT CAST(FROM_TZ(CAST(TO_DATE('2026-03-08 01:30:00', 'YYYY-MM-DD HH24:MI:SS') AS"
                    + " TIMESTAMP), "
                    + ny
                    + ") AT TIME ZONE '+00:00' AS TIMESTAMP) FROM DUAL"))
        .isEqualTo(LocalDateTime.parse("2026-03-08T06:30:00"));
    assertThat(
            ownerTimestamp(
                "SELECT CAST(FROM_TZ(CAST(TO_DATE('2026-03-08 03:30:00', 'YYYY-MM-DD HH24:MI:SS') AS"
                    + " TIMESTAMP), "
                    + ny
                    + ") AT TIME ZONE '+00:00' AS TIMESTAMP) FROM DUAL"))
        .isEqualTo(LocalDateTime.parse("2026-03-08T07:30:00"));
  }

  /**
   * 변환 식의 {@code AT TIME ZONE} 은 리터럴에만 쓰므로, 색인 있는 TSTZ 열의 Tibero 결함(열 {@code AT TIME ZONE} 조회 뒤 그
   * 열을 바꾸는 UPDATE 가 세션을 끊음 — api {@code TiberoWave5ProbeTest}, @Disabled 기록)과 무관하다. 우리 열은 시간대 없는
   * TIMESTAMP 다: 색인을 둔 열을 이 식으로 비교한 뒤 그 열을 바꾸는 UPDATE 가 정상 끝나는지 확인한다.
   */
  @Test
  void live_expression_onIndexedTimestamp_doesNotBreakUpdate() throws Exception {
    String s = "\"" + schema + "\"";
    exec(
        owner,
        "CREATE TABLE "
            + s
            + ".\"T3\" (\"ID\" NUMBER, \"_UPDATED_AT\" TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL)");
    exec(owner, "CREATE INDEX " + s + ".\"T3_UA\" ON " + s + ".\"T3\" (\"_UPDATED_AT\")");
    exec(owner, "INSERT INTO " + s + ".\"T3\" (\"ID\") VALUES (1)");
    owner.commit();
    assertThat(count(s, "T3", serverWallClock("2000-01-01 00:00:00.000000", SERVER_ZONE)))
        .isEqualTo(1);
    exec(owner, "UPDATE " + s + ".\"T3\" SET \"_UPDATED_AT\" = SYSTIMESTAMP, \"ID\" = 2");
    owner.commit();
    assertThat(count(s, "T3", serverWallClock("2000-01-01 00:00:00.000000", SERVER_ZONE)))
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
