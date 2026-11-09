package com.smartfirehub.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.analytics.dto.CreateChartRequest;
import com.smartfirehub.analytics.dto.CreateSavedQueryRequest;
import com.smartfirehub.analytics.service.ChartService;
import com.smartfirehub.analytics.service.SavedQueryService;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 차트 데이터·SQL 편집기·대시보드 일괄 데이터의 기다리는 동시 실행 상한(WD-599) 통합 테스트.
 *
 * <p>이 클래스만 전체 상한 1·기다림 3초로 띄운다(기본 4·10초로는 test 풀 2 에서 막히는 상황을 만들 수 없다). 느린 쿼리는
 * AnalyticsQueryConcurrencyIntegrationTest 와 같은 방법 — 풀 밖 원시 커넥션이 테이블에 ACCESS EXCLUSIVE 잠금을 쥐어 첫 차트
 * 요청을 허가·커넥션을 쥔 채 멈춰 세운다.
 *
 * <p><b>기다리는 요청이 커넥션을 쥐지 않는다는 증거.</b> test 풀은 2 — 첫 요청이 하나를 쥔다. 두 번째 요청이 게이트에서 기다리는 동안 무관한 조회가 곧바로
 * 200 이면 기다리는 요청은 커넥션을 쥐고 있지 않다(쥐었다면 무관한 조회가 풀 대기 30초에 걸린다).
 */
@AutoConfigureMockMvc
@TestPropertySource(
    properties = {
      "app.analytics.interactive-query-concurrency.max-total=1",
      "app.analytics.interactive-query-concurrency.max-per-user=3",
      "app.analytics.interactive-query-concurrency.wait-ms=3000"
    })
class InteractiveQueryConcurrencyIntegrationTest extends IntegrationTestBase {

  private static final String LIMIT_CODE = "QUERY_CONCURRENCY_LIMIT";
  private static final long WAIT_MS = 3000;

  @Autowired private MockMvc mockMvc;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private JwtTokenProvider jwt;
  @Autowired private ObjectMapper om;
  @Autowired private Environment env;
  @Autowired private SavedQueryService savedQueryService;
  @Autowired private ChartService chartService;

  private SecurityFixture fx;
  private final List<Long> users = new ArrayList<>();
  private final List<Long> roles = new ArrayList<>();
  private final List<Long> datasets = new ArrayList<>();
  private final List<String> tables = new ArrayList<>();
  private long creator;
  private long viewer;

  /** 원시 커넥션이 잠그는 테이블(첫 차트 요청이 여기서 멈춘다). */
  private String lockedTable;

  /** 잠기지 않는 테이블 — 두 번째 요청이 읽는다. */
  private String freeTable;

  /** freeTable 의 데이터셋 id — SQL 편집기 경로(/datasets/{id}/query)에 쓴다. */
  private long freeDatasetId;

  private long lockedChartId;
  private long freeChartId;

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    creator = fx.createUser("iqc_c");
    users.add(creator);
    String stamp = "iqc_" + System.nanoTime();
    lockedTable = createTable(stamp + "_lock");
    freeTable = createTable(stamp + "_free");
    freeDatasetId = datasets.get(datasets.size() - 1);
    viewer = fx.createUser("iqc_u");
    users.add(viewer);
    fx.removeUserRole(viewer);
    long rid =
        fx.createRole(
            "iqc_r_" + System.nanoTime(),
            fx.levelId("공개"),
            "analytics:read",
            "analytics:write",
            "data:read",
            "data:import");
    roles.add(rid);
    fx.assignRole(viewer, rid);
    lockedChartId = createChart("SELECT v FROM " + lockedTable);
    freeChartId = createChart("SELECT v FROM " + freeTable);
  }

  /** 공개 등급 데이터셋 테이블 하나(행 하나). 스키마까지 붙인 이름을 돌려준다. */
  private String createTable(String name) {
    String schema = DataSchema.current();
    dsl.execute("CREATE TABLE " + schema + "." + name + " (v int)");
    dsl.execute("INSERT INTO " + schema + "." + name + " VALUES (1)");
    tables.add(name);
    datasets.add(fx.createDatasetRow(name, fx.levelId("공개"), creator));
    return DataSchema.qualify(name);
  }

  /** 조회자 소유 저장 쿼리 + 차트를 만들고 차트 id 를 돌려준다. */
  private long createChart(String sql) {
    long queryId =
        savedQueryService
            .create(
                new CreateSavedQueryRequest(
                    "iqc_" + System.nanoTime(), null, sql, null, null, false),
                viewer)
            .id();
    return chartService
        .create(
            new CreateChartRequest(
                "iqc_" + System.nanoTime(), null, queryId, "BAR", Map.of(), false),
            viewer)
        .id();
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    for (long u : users) {
      inTenantFixture(
          () -> {
            dsl.execute("DELETE FROM chart WHERE created_by = ?", u);
            dsl.execute("DELETE FROM query_history WHERE user_id = ?", u);
            dsl.execute("DELETE FROM analytics_query_run WHERE user_id = ?", u);
            dsl.execute("DELETE FROM saved_query WHERE created_by = ?", u);
          });
    }
    datasets.forEach(fx::deleteDatasetRow);
    for (String t : tables) {
      dsl.execute("DROP TABLE IF EXISTS " + DataSchema.current() + "." + t);
    }
    users.forEach(fx::deleteUser);
    roles.forEach(fx::deleteRole);
  }

  private String token(long uid) {
    return "Bearer " + jwt.generateAccessToken(uid, "iqc", DEFAULT_TEST_TENANT_ID);
  }

  private MockHttpServletResponse chartData(long chartId) throws Exception {
    return mockMvc
        .perform(
            get("/api/v1/analytics/charts/" + chartId + "/data")
                .header("Authorization", token(viewer)))
        .andReturn()
        .getResponse();
  }

  /** SQL 편집기 실행(/datasets/{id}/query). */
  private MockHttpServletResponse sqlEditor(String sql) throws Exception {
    return mockMvc
        .perform(
            post("/api/v1/datasets/" + freeDatasetId + "/query")
                .header("Authorization", token(viewer))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("sql", sql, "maxRows", 100))))
        .andReturn()
        .getResponse();
  }

  private Map<?, ?> json(MockHttpServletResponse r) throws Exception {
    return om.readValue(r.getContentAsString(), Map.class);
  }

  /** 429 + QUERY_CONCURRENCY_LIMIT + 재시도 안내. */
  private void assertLimited(MockHttpServletResponse r) throws Exception {
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(429);
    Map<?, ?> body = json(r);
    assertThat(body.get("code")).isEqualTo(LIMIT_CODE);
    assertThat((String) body.get("message")).contains("다시 시도");
  }

  /** 별도 스레드에서 도는 요청과 그 결과. */
  private record Async(Thread thread, AtomicReference<MockHttpServletResponse> response) {}

  private Async startAsync(String name, Callable<MockHttpServletResponse> call) {
    AtomicReference<MockHttpServletResponse> resp = new AtomicReference<>();
    Thread t =
        new Thread(
            () -> {
              try {
                resp.set(call.call());
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            },
            name);
    t.start();
    return new Async(t, resp);
  }

  /** 잠금을 쥔 채 잠긴 테이블을 읽는 차트 요청을 띄우고, 잠금 대기(=허가·커넥션 보유)에 들어간 뒤 돌려준다. */
  private Async startBlockedChart(Connection raw) throws Exception {
    try (Statement st = raw.createStatement()) {
      st.execute("LOCK TABLE " + lockedTable + " IN ACCESS EXCLUSIVE MODE");
    }
    Async a = startAsync("iqc-blocked", () -> chartData(lockedChartId));
    awaitLockWait(raw);
    return a;
  }

  /** 다른 백엔드가 잠금 대기에 들어갈 때까지 기다린다(최대 30초). */
  private void awaitLockWait(Connection raw) throws Exception {
    long deadline = System.currentTimeMillis() + 30_000;
    while (System.currentTimeMillis() < deadline) {
      try (Statement st = raw.createStatement();
          ResultSet rs =
              st.executeQuery(
                  "SELECT pid FROM pg_stat_activity WHERE pid <> pg_backend_pid()"
                      + " AND datname = current_database() AND wait_event_type = 'Lock'")) {
        if (rs.next()) {
          return;
        }
      }
      Thread.sleep(50);
    }
    throw new AssertionError("첫 차트 요청이 30초 안에 잠금 대기에 들어가지 않았다");
  }

  /** 잠금을 풀고(롤백) 스레드가 끝나길 기다린다. */
  private void release(Connection raw, Async a) throws Exception {
    raw.rollback();
    a.thread().join(30_000);
    assertThat(a.thread().isAlive()).as("첫 요청이 잠금 해제 뒤 끝나야 한다").isFalse();
  }

  private Connection rawConnection() throws Exception {
    Connection c =
        DriverManager.getConnection(
            env.getRequiredProperty("spring.datasource.url"),
            env.getRequiredProperty("spring.datasource.username"),
            env.getRequiredProperty("spring.datasource.password"));
    c.setAutoCommit(false);
    return c;
  }

  @Test
  void secondChart_waitsWithoutHoldingConnection_andRunsAfterFirstFinishes() throws Exception {
    Async second;
    try (Connection raw = rawConnection()) {
      Async first = startBlockedChart(raw);
      try {
        second = startAsync("iqc-second", () -> chartData(freeChartId));
        Thread.sleep(500);
        // 상한 1 — 두 번째 차트는 바로 끝나지 않고 차례를 기다린다(게이트가 없으면 잠기지 않은 테이블이라 곧바로 200).
        assertThat(second.thread().isAlive()).as("두 번째 차트는 기다리는 중이어야 한다").isTrue();

        // 기다리는 동안 무관한 조회가 풀을 기다리지 않고 끝난다 — 기다리는 요청이 커넥션을 쥐지 않는다(풀 2 중 하나는 첫 요청 몫).
        long listStart = System.currentTimeMillis();
        MockHttpServletResponse list =
            mockMvc
                .perform(get("/api/v1/analytics/queries").header("Authorization", token(viewer)))
                .andReturn()
                .getResponse();
        assertThat(list.getStatus()).isEqualTo(200);
        assertThat(System.currentTimeMillis() - listStart).isLessThan(2_000);
        assertThat(second.thread().isAlive()).isTrue();
      } finally {
        release(raw, first);
      }
      assertThat(first.response().get().getStatus()).isEqualTo(200);
    }
    // 첫 요청이 허가를 반납하자 두 번째가 차례를 얻어 정상 결과.
    second.thread().join(10_000);
    assertThat(second.thread().isAlive()).isFalse();
    MockHttpServletResponse r = second.response().get();
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    assertThat(((Map<?, ?>) json(r).get("queryResult")).get("error")).isNull();
  }

  @Test
  void waitExceeded_chartAndSqlEditorAre429_missingDashboardIs404() throws Exception {
    try (Connection raw = rawConnection()) {
      Async first = startBlockedChart(raw);
      try {
        long start = System.currentTimeMillis();
        assertLimited(chartData(freeChartId));
        // 바로 거절하지 않고 wait-ms 만큼 기다렸다.
        assertThat(System.currentTimeMillis() - start).isGreaterThanOrEqualTo(WAIT_MS - 200);

        // SQL 편집기도 같은 게이트를 지난다.
        assertLimited(sqlEditor("SELECT v FROM " + freeTable));

        // 대시보드는 존재 확인 뒤 실행이 필요할 때만 허가를 잡는다(WD-625) — 게이트가 차 있어도 없는 id 는
        // 줄 서지 않고 곧바로 404. 캐시 미스 대시보드의 대기 뒤 429 는 InteractiveGatePermitPlacementTest 가 다룬다.
        long dashboardStart = System.currentTimeMillis();
        MockHttpServletResponse missingDashboard =
            mockMvc
                .perform(
                    get("/api/v1/analytics/dashboards/" + Long.MAX_VALUE + "/data")
                        .header("Authorization", token(viewer)))
                .andReturn()
                .getResponse();
        assertThat(missingDashboard.getStatus())
            .as(missingDashboard.getContentAsString())
            .isEqualTo(404);
        assertThat(System.currentTimeMillis() - dashboardStart).isLessThan(WAIT_MS);
      } finally {
        release(raw, first);
      }
    }
    // 허가가 돌아왔다 — 다음 실행은 곧바로 200.
    MockHttpServletResponse after = sqlEditor("SELECT v FROM " + freeTable);
    assertThat(after.getStatus()).as(after.getContentAsString()).isEqualTo(200);
  }

  @Test
  void permitIsReleased_afterNotFound_badSql_andQueryError() throws Exception {
    // 상한 1 — 아래 어느 경로에서든 허가가 새면 다음 요청이 3초 기다린 뒤 429 가 된다.
    assertThat(chartData(Long.MAX_VALUE).getStatus()).isEqualTo(404);

    MockHttpServletResponse bad = sqlEditor("SELEC broken");
    assertThat(bad.getStatus()).isBetween(400, 499);

    MockHttpServletResponse divZero = sqlEditor("SELECT 1 / (v - 1) FROM " + freeTable);
    assertThat(divZero.getStatus()).isNotEqualTo(429);

    long start = System.currentTimeMillis();
    MockHttpServletResponse ok = chartData(freeChartId);
    assertThat(ok.getStatus()).as(ok.getContentAsString()).isEqualTo(200);
    MockHttpServletResponse ok2 = sqlEditor("SELECT v FROM " + freeTable);
    assertThat(ok2.getStatus()).as(ok2.getContentAsString()).isEqualTo(200);
    assertThat(System.currentTimeMillis() - start).isLessThan(WAIT_MS);
  }
}
