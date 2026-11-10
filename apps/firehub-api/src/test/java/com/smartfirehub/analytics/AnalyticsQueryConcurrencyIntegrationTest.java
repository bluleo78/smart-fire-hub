package com.smartfirehub.analytics;

import static com.smartfirehub.support.AsyncMvc.completeAsync;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.analytics.dto.AnalyticsQueryResponse;
import com.smartfirehub.analytics.dto.CreateSavedQueryRequest;
import com.smartfirehub.analytics.service.ChartService;
import com.smartfirehub.analytics.service.SavedQueryService;
import com.smartfirehub.global.datastore.DataStoreDb;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.support.DataStoreTestConnections;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.MockMvc;

/**
 * 분석 쿼리 동시 실행 상한(WD-553) 통합 테스트 — test 프로필은 전체 상한 1(application-test.yml).
 *
 * <p><b>느린 쿼리를 결정적으로 만드는 법.</b> 풀 밖의 원시 JDBC 커넥션이 잠금 대상 테이블에 ACCESS EXCLUSIVE 잠금을 쥐면, 그 테이블을 읽는 첫
 * 애드혹 실행은 잠금이 풀릴 때까지 실행 중(허가 보유) 상태로 멈춘다. 잠금 커넥션을 Hikari 풀(test 크기 2) 밖에 두는 이유: 멈춘 첫 요청이 풀 하나를 쥐므로
 * 남은 하나로 두 번째 요청의 권한·자격 조회가 돌아야 한다. 두 번째 요청은 잠기지 않은 테이블을 읽는다 — 게이트가 없으면 곧바로 200 으로 끝나 단언이 깔끔하게
 * 실패한다(잠긴 테이블을 읽으면 수정 전에는 멈춰 버린다).
 *
 * <p>허가 누수 확인: 게이트는 컨텍스트 싱글턴이라 허가 하나가 새면 이후 모든 분석 실행이 429 가 된다. 실행 오류·취소(statement_timeout 과 같은
 * 57014) 뒤 다음 실행이 200 인지 본다.
 */
@AutoConfigureMockMvc
class AnalyticsQueryConcurrencyIntegrationTest extends IntegrationTestBase {

  private static final String LIMIT_CODE = "QUERY_CONCURRENCY_LIMIT";

  @Autowired private MockMvc mockMvc;
  @Autowired private DSLContext dsl;

  /** 데이터 테이블을 만들고 지우는 데이터 저장소 — 분석 쿼리는 여기서 돈다. 분리 모드에서는 플랫폼 DB 와 다른 서버다(WD-704). */
  @Autowired @DataStoreDb private DSLContext dataDsl;

  @Autowired private PasswordEncoder encoder;
  @Autowired private JwtTokenProvider jwt;
  @Autowired private ObjectMapper om;
  @Autowired private Environment env;
  @Autowired private SavedQueryService savedQueryService;
  @Autowired private ChartService chartService;
  @Autowired private ClearanceResolver clearanceResolver;

  private SecurityFixture fx;
  private final List<Long> users = new ArrayList<>();
  private final List<Long> roles = new ArrayList<>();
  private final List<Long> datasets = new ArrayList<>();
  private final List<String> tables = new ArrayList<>();
  private long creator;
  private long viewer;

  /** 원시 커넥션이 잠그는 테이블(첫 요청이 여기서 멈춘다). */
  private String lockedTable;

  /** 잠기지 않는 테이블 — 두 번째 요청·차트 경로가 읽는다. */
  private String freeTable;

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    creator = fx.createUser("aqc_c");
    users.add(creator);
    String stamp = "aqc_" + System.nanoTime();
    lockedTable = createTable(stamp + "_lock");
    freeTable = createTable(stamp + "_free");
    viewer = fx.createUser("aqc_u");
    users.add(viewer);
    fx.removeUserRole(viewer);
    long rid =
        fx.createRole(
            "aqc_r_" + System.nanoTime(),
            fx.levelId("공개"),
            "analytics:read",
            "analytics:write",
            "data:export");
    roles.add(rid);
    fx.assignRole(viewer, rid);
  }

  /** 공개 등급 데이터셋 테이블 하나(행 하나). 스키마까지 붙인 이름을 돌려준다 — 요청 뒤 TenantContext 가 비므로 미리 계산한다. */
  private String createTable(String name) {
    String schema = DataSchema.current();
    // 물리 테이블은 쿼리가 도는 데이터 저장소에 만든다(플랫폼 dsl 로 만들면 분리 모드에서 relation 이 없다).
    dataDsl.execute("CREATE TABLE " + schema + "." + name + " (v int)");
    dataDsl.execute("INSERT INTO " + schema + "." + name + " VALUES (1)");
    tables.add(name);
    datasets.add(fx.createDatasetRow(name, fx.levelId("공개"), creator));
    return DataSchema.qualify(name);
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    for (long u : users) {
      inTenantFixture(
          () -> {
            dsl.execute("DELETE FROM analytics_query_run WHERE user_id = ?", u);
            dsl.execute("DELETE FROM saved_query WHERE created_by = ?", u);
          });
    }
    datasets.forEach(fx::deleteDatasetRow);
    for (String t : tables) {
      dataDsl.execute("DROP TABLE IF EXISTS " + DataSchema.current() + "." + t);
    }
    users.forEach(fx::deleteUser);
    roles.forEach(fx::deleteRole);
  }

  private String token(long uid) {
    return "Bearer " + jwt.generateAccessToken(uid, "aqc", DEFAULT_TEST_TENANT_ID);
  }

  /** 애드혹 실행 — 응답 그대로(상태 단언은 호출자가). */
  private MockHttpServletResponse adHoc(String sql) throws Exception {
    return mockMvc
        .perform(
            post("/api/v1/analytics/queries/execute")
                .header("Authorization", token(viewer))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("sql", sql, "maxRows", 100))))
        .andReturn()
        .getResponse();
  }

  private MockHttpServletResponse savedExecute(long queryId) throws Exception {
    return mockMvc
        .perform(
            post("/api/v1/analytics/queries/" + queryId + "/execute")
                .header("Authorization", token(viewer))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"maxRows\":100}"))
        .andReturn()
        .getResponse();
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder exportRequest(
      String runId) {
    return post("/api/v1/analytics/queries/runs/" + runId + "/export")
        .header("Authorization", token(viewer))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"format\":\"CSV\"}");
  }

  private Map<?, ?> json(MockHttpServletResponse r) throws Exception {
    return om.readValue(r.getContentAsString(), Map.class);
  }

  /** 429 + QUERY_CONCURRENCY_LIMIT + 사용자 안내 문구. */
  private void assertLimited(MockHttpServletResponse r) throws Exception {
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(429);
    Map<?, ?> body = json(r);
    assertThat(body.get("code")).isEqualTo(LIMIT_CODE);
    assertThat((String) body.get("message")).contains("다시 시도");
  }

  /** 잠긴 테이블을 읽는 첫 요청 — 별도 스레드에서 돌고 결과를 담는다. */
  private record Blocked(Thread thread, AtomicReference<MockHttpServletResponse> response) {}

  /**
   * 풀 밖 원시 커넥션으로 잠금을 쥔 채 첫 애드혹 요청을 띄우고, 그 쿼리가 잠금 대기에 들어간 것(=허가를 쥔 채 실행 중)을 확인한 뒤 돌려준다. 호출자는 반드시
   * {@link #release} 로 잠금을 풀고 스레드를 기다린다.
   */
  private Blocked startBlockedQuery(Connection raw) throws Exception {
    try (Statement st = raw.createStatement()) {
      st.execute("LOCK TABLE " + lockedTable + " IN ACCESS EXCLUSIVE MODE");
    }
    AtomicReference<MockHttpServletResponse> resp = new AtomicReference<>();
    Thread t =
        new Thread(
            () -> {
              try {
                resp.set(adHoc("SELECT v FROM " + lockedTable));
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            },
            "aqc-blocked");
    t.start();
    awaitLockWait(raw);
    return new Blocked(t, resp);
  }

  /**
   * 잠긴 테이블을 읽는 다른 백엔드가 잠금 대기에 들어갈 때까지 기다린다(최대 30초). 그 백엔드 pid 를 돌려준다.
   *
   * <p>쿼리 문구로 거르지 않는다 — pgjdbc 확장 프로토콜은 파싱 단계에서 잠금을 기다리는 동안 pg_stat_activity.query 를 직전 문장(COMMIT
   * 등)으로 둔다(실측). 이 테이블 잠금은 원시 커넥션만 쥐므로 이 DB 에서 Lock 대기 중인 백엔드는 첫 요청뿐이다.
   */
  private int awaitLockWait(Connection raw) throws Exception {
    long deadline = System.currentTimeMillis() + 30_000;
    while (System.currentTimeMillis() < deadline) {
      try (Statement st = raw.createStatement();
          ResultSet rs =
              st.executeQuery(
                  "SELECT pid FROM pg_stat_activity WHERE pid <> pg_backend_pid()"
                      + " AND datname = current_database() AND wait_event_type = 'Lock'")) {
        if (rs.next()) {
          return rs.getInt(1);
        }
      }
      Thread.sleep(50);
    }
    StringBuilder diag = new StringBuilder();
    try (Statement st = raw.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT pid, state, wait_event_type, wait_event, left(query, 300) FROM pg_stat_activity"
                    + " WHERE pid <> pg_backend_pid() AND datname = current_database()")) {
      while (rs.next()) {
        diag.append(rs.getInt(1))
            .append(" | ")
            .append(rs.getString(2))
            .append(" | ")
            .append(rs.getString(3))
            .append(" | ")
            .append(rs.getString(4))
            .append(" | ")
            .append(rs.getString(5))
            .append("\n");
      }
    }
    throw new AssertionError("첫 쿼리가 30초 안에 잠금 대기에 들어가지 않았다\n" + diag);
  }

  /** 잠금을 풀고(롤백) 첫 요청 스레드가 끝나길 기다린다. */
  private void release(Connection raw, Blocked b) throws Exception {
    raw.rollback();
    b.thread().join(30_000);
    assertThat(b.thread().isAlive()).as("첫 요청이 잠금 해제 뒤 끝나야 한다").isFalse();
  }

  /** 잠금을 걸고 대기를 관찰할 풀 밖 원시 커넥션 — 쿼리가 도는 데이터 저장소에 연다({@link DataStoreTestConnections}). */
  private Connection rawConnection() throws Exception {
    return DataStoreTestConnections.openRuntime(env);
  }

  @Test
  void secondAdHoc_isRejectedWith429WhileFirstRuns_andRunsAfterwards() throws Exception {
    try (Connection raw = rawConnection()) {
      Blocked b = startBlockedQuery(raw);
      try {
        long start = System.currentTimeMillis();
        assertLimited(adHoc("SELECT v FROM " + freeTable));
        // 기다리지 않고 즉시 거절한다(풀 대기·쿼리 대기 없음).
        assertThat(System.currentTimeMillis() - start).isLessThan(5_000);
        // 그동안 무관한 조회(저장 쿼리 목록)는 풀을 기다리지 않고 끝난다 — 게이트가 핵심 요청 몫을 남긴다.
        long listStart = System.currentTimeMillis();
        MockHttpServletResponse list =
            mockMvc
                .perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                            "/api/v1/analytics/queries")
                        .header("Authorization", token(viewer)))
                .andReturn()
                .getResponse();
        assertThat(list.getStatus()).isEqualTo(200);
        assertThat(System.currentTimeMillis() - listStart).isLessThan(5_000);
      } finally {
        release(raw, b);
      }
      // 첫 요청은 잠금이 풀린 뒤 정상 결과.
      assertThat(b.response().get().getStatus()).isEqualTo(200);
      assertThat(json(b.response().get()).get("error")).isNull();
    }
    // 허가가 돌아왔다 — 다음 실행은 200.
    MockHttpServletResponse after = adHoc("SELECT v FROM " + freeTable);
    assertThat(after.getStatus()).isEqualTo(200);
    assertThat(json(after).get("error")).isNull();
  }

  @Test
  void savedQueryExecute_andExport_areGated() throws Exception {
    long queryId =
        savedQueryService
            .create(
                new CreateSavedQueryRequest(
                    "aqc_" + System.nanoTime(),
                    null,
                    "SELECT v FROM " + freeTable,
                    null,
                    null,
                    false),
                viewer)
            .id();
    // 게이트가 비어 있을 때 실행 기록(runId)을 만든다.
    MockHttpServletResponse first = adHoc("SELECT v FROM " + freeTable);
    assertThat(first.getStatus()).isEqualTo(200);
    String runId = (String) json(first).get("runId");
    assertThat(runId).isNotNull();

    try (Connection raw = rawConnection()) {
      Blocked b = startBlockedQuery(raw);
      try {
        // 저장 쿼리 실행은 바깥 트랜잭션 안에서 거절된다 — 롤백 예외로 바뀌지 않고 429 그대로 나와야 한다.
        assertLimited(savedExecute(queryId));
        assertLimited(mockMvc.perform(exportRequest(runId)).andReturn().getResponse());
      } finally {
        release(raw, b);
      }
    }
    // 허가 반납 뒤 둘 다 정상.
    MockHttpServletResponse saved = savedExecute(queryId);
    assertThat(saved.getStatus()).as(saved.getContentAsString()).isEqualTo(200);
    completeAsync(mockMvc, exportRequest(runId)).andExpect(status().isOk());
  }

  @Test
  void chartDataPath_isNotGated() throws Exception {
    var clearance = clearanceResolver.resolve(viewer);
    try (Connection raw = rawConnection()) {
      Blocked b = startBlockedQuery(raw);
      try {
        // 대시보드 위젯 병렬 로딩이 429 로 깨지지 않도록 차트 데이터 경로는 게이트 밖이다.
        TenantContext.set(DEFAULT_TEST_TENANT_ID);
        AnalyticsQueryResponse r =
            chartService.executeQueryForCache(clearance, "SELECT v FROM " + freeTable);
        assertThat(r.error()).isNull();
        assertThat(r.rows()).hasSize(1);
      } finally {
        release(raw, b);
      }
    }
  }

  @Test
  void permitIsReleased_afterQueryError_andAfterCancellation() throws Exception {
    // 실행 중 오류(0 으로 나누기)는 200 + error 로 끝난다 — 허가가 새면 다음 실행이 429 가 된다.
    MockHttpServletResponse err = adHoc("SELECT 1 / (v - 1) FROM " + freeTable);
    assertThat(err.getStatus()).isEqualTo(200);
    assertThat(json(err).get("error")).isNotNull();
    MockHttpServletResponse ok = adHoc("SELECT v FROM " + freeTable);
    assertThat(ok.getStatus()).as(ok.getContentAsString()).isEqualTo(200);
    assertThat(json(ok).get("error")).isNull();

    // 실행 중인 쿼리 취소 — statement_timeout 과 같은 57014(query_canceled) 실패 경로.
    try (Connection raw = rawConnection()) {
      Blocked b = startBlockedQuery(raw);
      try {
        // 취소 뒤 실행 서비스가 geometry 대비 메타데이터 조회로 같은 테이블을 한 번 더 읽어 다시 잠금 대기에 들 수 있다 — 요청이 끝날 때까지
        // 잠금 대기 백엔드를 거듭 취소한다(잠금은 끝까지 쥔 채라, 끝나는 이유는 취소뿐이다).
        long deadline = System.currentTimeMillis() + 30_000;
        while (b.thread().isAlive() && System.currentTimeMillis() < deadline) {
          try (Statement st = raw.createStatement()) {
            st.execute(
                "SELECT pg_cancel_backend(pid) FROM pg_stat_activity WHERE pid <> pg_backend_pid()"
                    + " AND datname = current_database() AND wait_event_type = 'Lock'");
          }
          b.thread().join(200);
        }
        assertThat(b.thread().isAlive()).as("취소된 첫 요청이 끝나야 한다").isFalse();
        MockHttpServletResponse canceled = b.response().get();
        // 취소된 실행은 성공 결과가 아니어야 한다(200 + error 또는 오류 상태).
        assertThat(canceled.getStatus() != 200 || json(canceled).get("error") != null)
            .as(canceled.getContentAsString())
            .isTrue();
      } finally {
        release(raw, b);
      }
    }
    MockHttpServletResponse after = adHoc("SELECT v FROM " + freeTable);
    assertThat(after.getStatus()).as(after.getContentAsString()).isEqualTo(200);
    assertThat(json(after).get("error")).isNull();
  }
}
