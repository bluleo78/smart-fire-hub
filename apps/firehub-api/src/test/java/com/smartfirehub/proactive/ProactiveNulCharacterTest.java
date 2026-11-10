package com.smartfirehub.proactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * WD-609: 리포트 양식·스마트 작업 저장 값에 NUL(U+0000)이 있으면 다른 API 와 같은 400 「저장할 수 없는 값이 있어 처리하지 못했습니다: …」 로
 * 응답해야 한다(수정 전 500).
 *
 * <p>각 경우에서 ① 상태 400 + 공통 문구 접두 ② 응답에 DB 원문(SQL·테이블명·PSQL 메시지)이 섞이지 않음 ③ 아무것도 저장되지 않음(생성 0건, 수정은 원래
 * 값 유지)을 함께 단언한다. JSON 본문의 {@code \\u0000} 은 Jackson 이 실제 NUL 문자로 풀어 DB 까지 그대로 간다.
 */
@AutoConfigureMockMvc
class ProactiveNulCharacterTest extends IntegrationTestBase {

  /** 전역 처리기가 DB 데이터 오류(SQLState 22xxx)에 붙이는 공통 문구 접두. */
  private static final String DATA_ERROR_PREFIX = "저장할 수 없는 값이 있어 처리하지 못했습니다";

  /** JSON 문자열 안의 NUL 이스케이프 — 자바 소스에서 실제 NUL 이 아니라 6글자 이스케이프로 보내기 위해 역슬래시를 겹친다. */
  private static final String NUL = "\\u0000";

  @Autowired private MockMvc mockMvc;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private JwtTokenProvider jwt;
  @Autowired private ObjectMapper om;

  private SecurityFixture fx;
  private final List<Long> users = new ArrayList<>();
  private final List<Long> roles = new ArrayList<>();
  private long userId;
  private String token;

  /** 스마트 작업 읽기·쓰기 권한만 가진 사용자 한 명을 만든다(기본 USER 역할은 뗀다). */
  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    userId = fx.createUser("nul_u");
    users.add(userId);
    fx.removeUserRole(userId);
    long rid =
        fx.createRole(
            "nul_r_" + System.nanoTime(), fx.levelId("공개"), "proactive:read", "proactive:write");
    roles.add(rid);
    fx.assignRole(userId, rid);
    token = "Bearer " + jwt.generateAccessToken(userId, "nul" + userId, DEFAULT_TEST_TENANT_ID);
  }

  /** 이 테스트 사용자가 만든 작업·양식과 픽스처 사용자·역할을 지운다. */
  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> {
          dsl.deleteFrom(table(name("proactive_job")))
              .where(field(name("user_id"), Long.class).in(users))
              .execute();
          dsl.deleteFrom(table(name("report_template")))
              .where(field(name("user_id"), Long.class).in(users))
              .execute();
        });
    users.forEach(fx::deleteUser);
    roles.forEach(fx::deleteRole);
  }

  // ── 리포트 양식 ─────────────────────────────────────────────

  @Test
  void createTemplate_nameWithNul_returns400_andSavesNothing() throws Exception {
    MockHttpServletResponse res =
        send(
            post("/api/v1/proactive/templates"),
            "{\"name\":\"a" + NUL + "b\",\"sections\":[{\"key\":\"a\",\"label\":\"A\"}]}");

    assertDataError(res);
    assertThat(count("report_template")).isZero();
  }

  @Test
  void updateTemplate_nameWithNul_returns400_andKeepsOriginal() throws Exception {
    long id = createTemplate("원래양식");

    MockHttpServletResponse res =
        send(put("/api/v1/proactive/templates/" + id), "{\"name\":\"a" + NUL + "b\"}");

    assertDataError(res);
    assertThat(column("report_template", id, "name")).isEqualTo("원래양식");
  }

  /** 섹션(JSONB) 안 문자열의 NUL — PG jsonb 는 22P05 로 거부한다. 텍스트 열(22021)과 같은 NUL 문구여야 한다. */
  @Test
  void createTemplate_sectionsWithNul_returns400_andSavesNothing() throws Exception {
    MockHttpServletResponse res =
        send(
            post("/api/v1/proactive/templates"),
            "{\"name\":\"n\",\"sections\":[{\"key\":\"a\",\"label\":\"A" + NUL + "B\"}]}");

    assertDataError(res);
    assertThat(count("report_template")).isZero();
  }

  @Test
  void updateTemplate_sectionsWithNul_returns400_andKeepsOriginal() throws Exception {
    long id = createTemplate("원래양식");

    MockHttpServletResponse res =
        send(
            put("/api/v1/proactive/templates/" + id),
            "{\"name\":\"원래양식\",\"sections\":[{\"key\":\"z\",\"label\":\"Z" + NUL + "Y\"}]}");

    assertDataError(res);
    // 원래 섹션(key a, label A)이 그대로이고 새 섹션은 들어가지 않았다.
    assertThat(column("report_template", id, "sections"))
        .contains("\"label\": \"A\"")
        .doesNotContain("Z");
  }

  // ── 스마트 작업 ─────────────────────────────────────────────

  @Test
  void createJob_nameWithNul_returns400_andSavesNothing() throws Exception {
    assertCreateJobRejected(
        "{\"name\":\"a"
            + NUL
            + "b\",\"prompt\":\"p\",\"config\":{},\"cronExpression\":\"0 9 * * *\",\"enabled\":false}");
  }

  @Test
  void createJob_promptWithNul_returns400_andSavesNothing() throws Exception {
    assertCreateJobRejected(
        "{\"name\":\"n\",\"prompt\":\"p"
            + NUL
            + "q\",\"config\":{},\"cronExpression\":\"0 9 * * *\",\"enabled\":false}");
  }

  @Test
  void createJob_configStringWithNul_returns400_andSavesNothing() throws Exception {
    assertCreateJobRejected(
        "{\"name\":\"n\",\"prompt\":\"p\",\"config\":{\"a\":\"x"
            + NUL
            + "y\"},\"cronExpression\":\"0 9 * * *\",\"enabled\":false}");
  }

  @Test
  void updateJob_nameWithNul_returns400_andKeepsOriginal() throws Exception {
    long id = createJob();

    MockHttpServletResponse res =
        send(put("/api/v1/proactive/jobs/" + id), "{\"name\":\"a" + NUL + "b\"}");

    assertDataError(res);
    assertThat(column("proactive_job", id, "name")).isEqualTo("원래작업");
    assertThat(column("proactive_job", id, "prompt")).isEqualTo("원래프롬프트");
  }

  @Test
  void updateJob_promptWithNul_returns400_andKeepsOriginal() throws Exception {
    long id = createJob();

    MockHttpServletResponse res =
        send(put("/api/v1/proactive/jobs/" + id), "{\"prompt\":\"p" + NUL + "q\"}");

    assertDataError(res);
    assertThat(column("proactive_job", id, "name")).isEqualTo("원래작업");
    assertThat(column("proactive_job", id, "prompt")).isEqualTo("원래프롬프트");
  }

  @Test
  void updateJob_configStringWithNul_returns400_andKeepsOriginal() throws Exception {
    long id = createJob();

    MockHttpServletResponse res =
        send(put("/api/v1/proactive/jobs/" + id), "{\"config\":{\"a\":\"x" + NUL + "y\"}}");

    assertDataError(res);
    assertThat(column("proactive_job", id, "config")).doesNotContain("x");
  }

  // ── 도우미 ─────────────────────────────────────────────────

  /** 작업 생성이 400 공통 문구로 거부되고 작업이 한 건도 남지 않았는지. */
  private void assertCreateJobRejected(String body) throws Exception {
    MockHttpServletResponse res = send(post("/api/v1/proactive/jobs"), body);
    assertDataError(res);
    assertThat(count("proactive_job")).isZero();
  }

  /**
   * 400 + 공통 문구 접두 + NUL 사유 문구이고, 응답 본문에 DB 원문(SQL·테이블명·드라이버 메시지)이 없는지. 텍스트 열은 22021, JSONB 안 문자열은
   * 22P05 로 거부되며 둘 다 「NUL 문자」 사유로 안내돼야 한다(WD-609).
   */
  private void assertDataError(MockHttpServletResponse res) throws Exception {
    String body = res.getContentAsString();
    assertThat(res.getStatus()).as(body).isEqualTo(400);
    JsonNode json = om.readTree(body);
    assertThat(json.path("message").asText())
        .as(body)
        .startsWith(DATA_ERROR_PREFIX)
        .contains("NUL 문자");
    assertThat(body)
        .doesNotContain("insert into")
        .doesNotContain("update \"")
        .doesNotContain("report_template")
        .doesNotContain("proactive_job")
        .doesNotContain("UTF8")
        .doesNotContain("0x00")
        .doesNotContain("jOOQ");
  }

  /** 이 사용자의 양식 하나를 만들고 id 를 돌려준다. */
  private long createTemplate(String templateName) throws Exception {
    MockHttpServletResponse res =
        send(
            post("/api/v1/proactive/templates"),
            "{\"name\":\"" + templateName + "\",\"sections\":[{\"key\":\"a\",\"label\":\"A\"}]}");
    assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(201);
    return om.readTree(res.getContentAsString()).path("id").asLong();
  }

  /** 이 사용자의 비활성 작업 하나를 만들고 id 를 돌려준다. 정기 작업은 실행 주기가 있어야 저장된다(WD-607). */
  private long createJob() throws Exception {
    MockHttpServletResponse res =
        send(
            post("/api/v1/proactive/jobs"),
            "{\"name\":\"원래작업\",\"prompt\":\"원래프롬프트\",\"config\":{},\"cronExpression\":\"0 9 * * *\",\"enabled\":false}");
    assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(201);
    return om.readTree(res.getContentAsString()).path("id").asLong();
  }

  private MockHttpServletResponse send(MockHttpServletRequestBuilder req, String body)
      throws Exception {
    req.header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(body);
    return mockMvc.perform(req).andReturn().getResponse();
  }

  /** 이 사용자가 가진 행 수(RLS 테넌트 컨텍스트 안에서 센다). */
  private int count(String tableName) {
    int[] n = new int[1];
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () ->
            n[0] =
                dsl.fetchCount(
                    table(name(tableName)), field(name("user_id"), Long.class).eq(userId)));
    return n[0];
  }

  /** 한 행의 열 값을 문자열로 읽는다(JSONB 는 텍스트로). */
  private String column(String tableName, long id, String columnName) {
    String[] v = new String[1];
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () ->
            v[0] =
                dsl.select(field(name(columnName)).cast(String.class))
                    .from(table(name(tableName)))
                    .where(field(name("id"), Long.class).eq(id))
                    .fetchOne(0, String.class));
    return v[0];
  }
}
