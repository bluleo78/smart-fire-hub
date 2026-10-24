package com.smartfirehub.dbexecutor.live;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dbexecutor.config.DbExecutorProperties;
import com.smartfirehub.dbexecutor.store.StoreDescriptor;
import com.smartfirehub.dbexecutor.store.StoreDescriptorClient;
import com.smartfirehub.dbexecutor.store.StoreUnavailableException;
import com.smartfirehub.dbexecutor.store.TenantPools;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Tibero 실서버 HTTP 끝-끝 테스트(WD-77 Task 8) — 실제 Tomcat 에 {@code java.net.http.HttpClient} 로 요청을 보내 인증
 * 필터·JSON 직렬화· 실행·탐지를 한 번에 확인한다. 컨트롤러를 직접 부르는 {@link TiberoLiveSqlTest} 가 못 보는 구간(필터, 응답 키)을 덮는다.
 *
 * <p>{@code TIBERO_TEST_*} 나 드라이버가 없으면 건너뛴다(스프링 컨텍스트는 접속 없이 뜬다). 실행은 {@code
 * scripts/tibero-test.sh}. 접속 값은 출력하지 않는다. 공유 서버 규칙은 {@link TiberoLiveSqlTest} 와 같다 — 예약 대역 id 로 전용
 * 계정 하나만 만들고, 같은 이름이 있으면 거절하며, 끝나면 그 계정만 지운다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"app.internal-token=live-http-token", "app.load-token=live-load-token"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoLiveHttpTest {

  private static final String TOKEN = "live-http-token";

  /** 적재 전용 토큰 — /execute/load 외 창구는 401 이어야 한다(스펙 §3.4). */
  private static final String LOAD_TOKEN = "live-load-token";

  /** 테스트 더블이 돌려줄 기술자(BeforeAll 에서 계정을 만든 뒤 채운다). */
  private static volatile StoreDescriptor liveDescriptor;

  /** api 호출 대신 {@link #liveDescriptor} 를 돌려주는 기술자 클라이언트로 바꾼다(그 외 전부 실제 빈). */
  @TestConfiguration
  static class Doubles {
    @Bean
    @Primary
    StoreDescriptorClient descriptorDouble() {
      return new StoreDescriptorClient(
          new DbExecutorProperties(
              TOKEN,
              "load-tok",
              null,
              "http://127.0.0.1:9",
              60,
              new DbExecutorProperties.Pool(8, 4, 2),
              false),
          new ObjectMapper()) {
        @Override
        public StoreDescriptor get(long id, Duration budget) {
          StoreDescriptor d = liveDescriptor;
          if (d == null || id != d.tenantId()) {
            throw new StoreUnavailableException("404");
          }
          return d;
        }
      };
    }
  }

  @LocalServerPort private int serverPort;

  @Autowired private ObjectMapper json;

  // OWNER 풀. 읽기 전용 풀(readOnlyPools)은 RO 실서버 검증(Task 7)에서 다룬다.
  @Autowired
  @Qualifier("tenantPools")
  private TenantPools pools;

  private final HttpClient http = HttpClient.newHttpClient();

  private TiberoLiveAccount account;
  private long tenantId;
  private String schema;
  private Connection owner;

  @BeforeAll
  void setUp() throws Exception {
    account = TiberoLiveAccount.open();
    // 앱의 테넌트 풀이 계정으로 세션을 잡고 있으면 DROP USER 가 거절되므로 먼저 닫는다.
    account.closeBeforeDrop(pools::closeAll);
    tenantId = account.tenantId();
    schema = account.schema();
    owner = account.owner();

    // api createTable 과 같은 모양의 출력 테이블·시퀀스.
    String s = "\"" + schema + "\"";
    TiberoLiveAccount.exec(
        owner, "CREATE SEQUENCE " + s + ".\"SQ_HTTP\" START WITH 1 INCREMENT BY 1 NOCACHE");
    TiberoLiveAccount.exec(
        owner,
        "CREATE TABLE "
            + s
            + ".\"ORDERS\" (\"ID\" NUMBER(19) PRIMARY KEY, \"ID_KEY\" NUMBER(19), \"AMOUNT\""
            + " NUMBER(18,6), \"NAME\" VARCHAR2(100 CHAR), \"_UPDATED_AT\" TIMESTAMP DEFAULT"
            + " SYSTIMESTAMP NOT NULL)");
    // 이전 값이 남았는지 확인할 수 있도록 먼저 1행을 넣어 둔다(REPLACE 가 지워야 한다).
    TiberoLiveAccount.exec(
        owner,
        "INSERT INTO "
            + s
            + ".\"ORDERS\" (\"ID\", \"ID_KEY\", \"AMOUNT\", \"NAME\") VALUES (0, 99, 1, 'old')");
    owner.commit();

    liveDescriptor = account.descriptor();
  }

  /** 계정 정리는 {@link TiberoLiveAccount#close()} 가 단계별로 실패에 안전하게 처리한다. */
  @AfterAll
  void tearDown() throws Exception {
    liveDescriptor = null;
    if (account != null) {
      account.close();
    }
  }

  // ------------------------------------------------------------------ 테스트

  /** 적재 토큰은 실서버 스택에서도 SQL·탐지 창구를 열지 못한다(필터 → 컨트롤러 전 구간). */
  @Test
  void 적재토큰은_sql과_probe에서_401() throws Exception {
    assertThat(post("/execute/sql", sqlBody(replaceSelect()), LOAD_TOKEN).status).isEqualTo(401);
    assertThat(post("/execute/probe", probeBody(), LOAD_TOKEN).status).isEqualTo(401);
  }

  @Test
  void live_http_replace() throws Exception {
    JsonNode r = post("/execute/sql", sqlBody(replaceSelect()), TOKEN).json;
    assertThat(r.get("success").asBoolean()).as(r.toString()).isTrue();
    assertThat(r.get("row_count").asInt()).isEqualTo(2);
    assertThat(rowKeys()).containsExactly(1L, 2L);
  }

  @Test
  void live_http_probe() throws Exception {
    JsonNode r = post("/execute/probe", probeBody(), TOKEN).json;
    assertThat(r.get("success").asBoolean()).as(r.toString()).isTrue();
    List<String> names = new ArrayList<>();
    r.get("columns").forEach(c -> names.add(c.get("name").asText()));
    // 탐지 이름은 바꾸지 않고 온다(F2) — 따옴표 없는 별칭은 Tibero 가 대문자로 접는다.
    assertThat(names).containsExactly("A", "B");
  }

  @Test
  void live_http_noToken_401() throws Exception {
    assertThat(post("/execute/sql", sqlBody(replaceSelect()), null).status).isEqualTo(401);
    assertThat(post("/execute/probe", probeBody(), "wrong").status).isEqualTo(401);
  }

  @Test
  void live_http_responseKeys_matchApiRecords() throws Exception {
    // api SqlExecuteResult / ProbeResult 가 읽는 키 집합. 직렬화는 null 도 쓰므로 실제로는 정확히 같아야 한다.
    Set<String> sqlKeys =
        Set.of("success", "rows", "columns", "row_count", "execution_log", "error", "db_error");
    Set<String> probeKeys = Set.of("success", "columns", "error", "db_error");
    Set<String> columnKeys = Set.of("name", "jdbc_type", "type_name", "precision", "scale");

    JsonNode sql = post("/execute/sql", sqlBody(replaceSelect()), TOKEN).json;
    System.out.println("LIVE sql keys=" + fieldSet(sql));
    assertThat(fieldSet(sql)).isEqualTo(sqlKeys);

    JsonNode probe = post("/execute/probe", probeBody(), TOKEN).json;
    System.out.println("LIVE probe keys=" + fieldSet(probe));
    assertThat(fieldSet(probe)).isEqualTo(probeKeys);
    assertThat(probe.get("columns")).isNotEmpty();
    for (JsonNode c : probe.get("columns")) {
      System.out.println("LIVE probe column keys=" + fieldSet(c));
      assertThat(fieldSet(c)).isEqualTo(columnKeys);
    }

    // 실패 응답(db_error 채움)도 같은 키 집합 + db_error 하위 키 확인.
    JsonNode bad =
        post(
                "/execute/sql",
                json.writeValueAsString(
                    Map.of("tenantId", tenantId, "query", "SELECT * FROM NO_SUCH_TABLE_X")),
                TOKEN)
            .json;
    System.out.println("LIVE failure keys=" + fieldSet(bad));
    assertThat(bad.get("success").asBoolean()).isFalse();
    assertThat(fieldSet(bad)).isEqualTo(sqlKeys);
    assertThat(fieldSet(bad.get("db_error"))).isEqualTo(Set.of("code", "sql_state", "message"));
  }

  // ------------------------------------------------------------------ 도우미

  /**
   * api {@code TiberoPipelineSql} 의 REPLACE 와 같은 모양(선행 DELETE + INSERT … SELECT … FROM (\n본문\n)
   * src).
   */
  private String replaceSelect() {
    return "INSERT INTO \""
        + schema
        + "\".\"ORDERS\" (\"ID\", \"ID_KEY\", \"AMOUNT\", \"NAME\", \"_UPDATED_AT\") SELECT \""
        + schema
        + "\".\"SQ_HTTP\".NEXTVAL, src.\"ID_KEY\", src.\"AMOUNT\", src.\"NAME\", SYSTIMESTAMP FROM"
        + " (\nSELECT 1 AS id_key, 10.5 AS amount, 'a' AS name FROM DUAL UNION ALL SELECT 2, 20,"
        + " 'b' FROM DUAL\n) src";
  }

  private String sqlBody(String query) throws Exception {
    return json.writeValueAsString(
        Map.of(
            "tenantId",
            tenantId,
            "query",
            query,
            "preStatements",
            List.of("DELETE FROM \"" + schema + "\".\"ORDERS\"")));
  }

  private String probeBody() throws Exception {
    return json.writeValueAsString(
        Map.of("tenantId", tenantId, "query", "SELECT 1 AS a, 'x' b FROM DUAL"));
  }

  private record Reply(int status, JsonNode json) {}

  private Reply post(String path, String body, String token) throws Exception {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create("http://localhost:" + serverPort + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (token != null) {
      b.header("Authorization", "Internal " + token);
    }
    HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    JsonNode node = res.body() == null || res.body().isBlank() ? null : tryParse(res.body());
    return new Reply(res.statusCode(), node);
  }

  private JsonNode tryParse(String body) {
    try {
      return json.readTree(body);
    } catch (Exception e) {
      return null;
    }
  }

  private static Set<String> fieldSet(JsonNode n) {
    Set<String> out = new HashSet<>();
    n.fieldNames().forEachRemaining(out::add);
    return out;
  }

  private List<Long> rowKeys() throws SQLException {
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
}
