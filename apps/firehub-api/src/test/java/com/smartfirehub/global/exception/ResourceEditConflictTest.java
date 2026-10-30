package com.smartfirehub.global.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.proactive.repository.ProactiveJobRepository;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.support.TestUsers;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 편집 충돌(낙관적 잠금) — 차트·대시보드 설정·저장 쿼리·리포트 양식·스마트 작업(WD-364). HTTP 왕복으로 검증한다.
 *
 * <p>결함: 다섯 편집 화면 모두 수정 요청이 폼의 모든 필드를 보내, 두 사람(또는 두 탭)이 같은 항목을 고쳐 저장하면 나중 저장이 먼저 저장된 변경을 경고 없이
 * 되돌렸다. 파이프라인(WD-353)과 같은 장치를 붙였다 — 웹이 편집 기준 버전(상세의 {@code updatedAt})을 {@code expectedUpdatedAt}
 * 으로 보내고, 다르면 409 EDIT_CONFLICT 로 거부한다.
 *
 * <p>서비스 호출이 아니라 MockMvc 로 하는 이유: 상세 응답의 {@code updatedAt} 문자열을 그대로 되돌려 보냈을 때 같다고 판정되는지(직렬화 정밀도 —
 * PG 마이크로초 ↔ JSON ↔ LocalDateTime)가 이 장치의 핵심이라, 객체를 직접 넘기는 테스트로는 확인되지 않는다.
 *
 * <p>클래스 레벨 {@code @Transactional} 을 쓰지 않는다 — 요청이 각자 커밋되어야 다음 요청이 바뀐 버전을 본다. 정리는 {@link
 * #tearDown()}.
 */
@AutoConfigureMockMvc
class ResourceEditConflictTest extends IntegrationTestBase {

  @Autowired private MockMvc mockMvc;
  @Autowired private DSLContext dsl;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JwtTokenProvider jwt;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private ProactiveJobRepository proactiveJobRepository;

  private SecurityFixture fx;
  private long owner;
  private long stranger;

  /** 리소스별 HTTP 경로·수정 본문 — 각 리소스에서 "고칠 필드" 하나(설명 또는 프롬프트)만 바꿔 본다. */
  enum Resource {
    CHART("/api/v1/analytics/charts", "description", "이 차트는"),
    DASHBOARD("/api/v1/analytics/dashboards", "description", "이 대시보드는"),
    SAVED_QUERY("/api/v1/analytics/queries", "description", "이 쿼리는"),
    REPORT_TEMPLATE("/api/v1/proactive/templates", "description", "이 리포트 양식은"),
    PROACTIVE_JOB("/api/v1/proactive/jobs", "prompt", "이 작업은");

    final String base;
    final String field;
    final String messagePrefix;

    Resource(String base, String field, String messagePrefix) {
      this.base = base;
      this.field = field;
      this.messagePrefix = messagePrefix;
    }
  }

  static Stream<Resource> resources() {
    return Stream.of(Resource.values());
  }

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, passwordEncoder);
    owner = fx.createUser("wd364-owner-");
    stranger = fx.createUser("wd364-other-");
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, owner, DEFAULT_TEST_TENANT_ID, "ADMIN");
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, stranger, DEFAULT_TEST_TENANT_ID, "ADMIN");
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    TenantRlsTestSupport.cleanupAll(
        () ->
            inTenantFixture(
                DEFAULT_TEST_TENANT_ID,
                () -> {
                  // 차트는 저장 쿼리 CASCADE 로도 지워지지만, 위젯·대시보드를 먼저 지워 FK 순서를 분명히 한다.
                  dsl.execute("delete from dashboard where created_by in (?, ?)", owner, stranger);
                  dsl.execute("delete from chart where created_by in (?, ?)", owner, stranger);
                  dsl.execute(
                      "delete from saved_query where created_by in (?, ?)", owner, stranger);
                  dsl.execute("delete from proactive_job where user_id in (?, ?)", owner, stranger);
                  dsl.execute(
                      "delete from report_template where user_id in (?, ?)", owner, stranger);
                }),
        () -> fx.deleteUser(owner),
        () -> fx.deleteUser(stranger));
  }

  /** 상세의 updatedAt 문자열을 그대로 보내면 통과하고, 응답의 새 updatedAt 으로 이어 저장해도 통과한다(자기 저장과 충돌하지 않는다). */
  @ParameterizedTest
  @MethodSource("resources")
  void saveWithDetailVersion_succeeds_andReturnedVersionChainsNextSave(Resource r)
      throws Exception {
    long id = create(r);
    String v1 = detail(r, id).get("updatedAt").asText();

    MvcResult first = save(r, id, "값 A", v1, owner);
    assertThat(first.getResponse().getStatus()).as(body(first)).isEqualTo(200);
    String v2 = readTree(first).get("updatedAt").asText();
    assertThat(v2).as("응답은 새 버전을 돌려준다").isNotEqualTo(v1);
    assertThat(v2)
        .as("응답 버전은 저장된 값(상세)과 같아야 한다")
        .isEqualTo(detail(r, id).get("updatedAt").asText());

    MvcResult second = save(r, id, "값 B", v2, owner);
    assertThat(second.getResponse().getStatus()).as(body(second)).isEqualTo(200);
    assertThat(detail(r, id).get(r.field).asText()).isEqualTo("값 B");
  }

  /** 결함 재현: 편집 시작 뒤 다른 곳에서 저장됐으면 낡은 버전의 저장은 409 EDIT_CONFLICT 이고, 먼저 저장된 변경이 그대로 남는다. */
  @ParameterizedTest
  @MethodSource("resources")
  void saveWithStaleVersion_rejected409_andEarlierSaveKept(Resource r) throws Exception {
    long id = create(r);
    String base = detail(r, id).get("updatedAt").asText();

    // 다른 탭이 먼저 같은 기준으로 저장했다.
    MvcResult other = save(r, id, "다른 탭의 값", base, owner);
    assertThat(other.getResponse().getStatus()).as(body(other)).isEqualTo(200);

    MvcResult stale = save(r, id, "내 값", base, owner);
    assertThat(stale.getResponse().getStatus()).as(body(stale)).isEqualTo(409);
    JsonNode res = readTree(stale);
    assertThat(res.get("code").asText()).isEqualTo(EditConflictException.CODE);
    assertThat(res.get("message").asText()).startsWith(r.messagePrefix).contains("다른 곳에서 변경되었습니다");
    assertThat(detail(r, id).get(r.field).asText()).as("먼저 저장된 변경이 되돌려지지 않는다").isEqualTo("다른 탭의 값");
  }

  /** 기준 버전 없이 보내면(AI 도구 등 기존 호출부, 「내 변경으로 덮어쓰기」) 비교하지 않고 저장된다. */
  @ParameterizedTest
  @MethodSource("resources")
  void saveWithoutVersion_skipsCheck_andOverwrites(Resource r) throws Exception {
    long id = create(r);
    String base = detail(r, id).get("updatedAt").asText();
    assertThat(save(r, id, "다른 탭의 값", base, owner).getResponse().getStatus()).isEqualTo(200);

    MvcResult overwrite = save(r, id, "덮어쓴 값", null, owner);
    assertThat(overwrite.getResponse().getStatus()).as(body(overwrite)).isEqualTo(200);
    assertThat(detail(r, id).get(r.field).asText()).isEqualTo("덮어쓴 값");
  }

  /** 남의 항목에 낡은 버전을 보내도 409 가 아니라 지금과 같은 404 다 — 충돌 판정이 소유자 확인보다 먼저면 409 로 존재가 드러난다. 주인의 값도 그대로다. */
  @ParameterizedTest
  @MethodSource("resources")
  void strangerWithStaleVersion_gets404_not409(Resource r) throws Exception {
    long id = create(r);
    String base = detail(r, id).get("updatedAt").asText();
    assertThat(save(r, id, "주인의 값", base, owner).getResponse().getStatus()).isEqualTo(200);

    MvcResult res = save(r, id, "남의 값", base, stranger);
    assertThat(res.getResponse().getStatus()).as(body(res)).isEqualTo(404);
    assertThat(detail(r, id).get(r.field).asText()).isEqualTo("주인의 값");
  }

  /**
   * 스마트 작업이 실행돼도(마지막 실행 기록) 수정 시각은 그대로다 — 실행마다 버전이 바뀌면 편집 중 예약 실행·「지금 실행」이 한 번 돌았을 뿐인데 저장이 편집 충돌로
   * 거부된다(WD-364).
   */
  @Test
  void proactiveJobExecutionRecord_doesNotBumpVersion() throws Exception {
    long id = create(Resource.PROACTIVE_JOB);
    String base = detail(Resource.PROACTIVE_JOB, id).get("updatedAt").asText();

    inTenantFixture(
        DEFAULT_TEST_TENANT_ID,
        () -> proactiveJobRepository.updateLastExecuted(id, LocalDateTime.now(), null));

    assertThat(detail(Resource.PROACTIVE_JOB, id).get("updatedAt").asText()).isEqualTo(base);
    MvcResult res = save(Resource.PROACTIVE_JOB, id, "실행 뒤 저장", base, owner);
    assertThat(res.getResponse().getStatus()).as(body(res)).isEqualTo(200);
  }

  // ── 헬퍼 ──────────────────────────────────────────────────────────────────

  /** 주인 소유의 리소스를 만든다(차트는 저장 쿼리를 먼저 만든다). 공유하지 않는다 — 남에게는 없는 항목과 같아야 한다. */
  private long create(Resource r) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    String name = "wd364-" + System.nanoTime();
    switch (r) {
      case SAVED_QUERY -> {
        body.put("name", name);
        body.put("description", "처음 값");
        body.put("sqlText", "SELECT 1 AS v");
        body.put("isShared", false);
      }
      case CHART -> {
        body.put("name", name);
        body.put("description", "처음 값");
        body.put("savedQueryId", create(Resource.SAVED_QUERY));
        body.put("chartType", "BAR");
        body.put("config", Map.of("xAxis", "v", "yAxis", List.of("v")));
        body.put("isShared", false);
      }
      case DASHBOARD -> {
        body.put("name", name);
        body.put("description", "처음 값");
        body.put("isShared", false);
      }
      case REPORT_TEMPLATE -> {
        body.put("name", name);
        body.put("description", "처음 값");
        body.put("sections", List.of(Map.of("key", "summary", "label", "요약", "type", "text")));
      }
      case PROACTIVE_JOB -> {
        body.put("name", name);
        body.put("prompt", "처음 값");
        // 꺼 두어 예약 실행에 등록되지 않게 한다
        body.put("enabled", false);
        body.put("config", Map.of());
      }
    }
    MvcResult created =
        mockMvc
            .perform(
                post(r.base)
                    .header("Authorization", token(owner))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
            .andReturn();
    assertThat(created.getResponse().getStatus()).as(body(created)).isEqualTo(201);
    return readTree(created).get("id").asLong();
  }

  /** 고칠 필드 하나만 바꾸는 수정 요청 — expectedUpdatedAt 은 상세에서 받은 문자열을 그대로 싣는다(null 이면 뺀다). */
  private MvcResult save(Resource r, long id, String value, String expectedUpdatedAt, long user)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    // 양식은 이름이 필수다(@NotBlank) — 주인의 상세에서 읽어 그대로 보낸다.
    if (r == Resource.REPORT_TEMPLATE) {
      body.put("name", detail(r, id).get("name").asText());
    }
    body.put(r.field, value);
    if (expectedUpdatedAt != null) {
      body.put("expectedUpdatedAt", expectedUpdatedAt);
    }
    return mockMvc
        .perform(
            put(r.base + "/" + id)
                .header("Authorization", token(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andReturn();
  }

  /** 주인 기준 상세. */
  private JsonNode detail(Resource r, long id) throws Exception {
    MvcResult result =
        mockMvc.perform(get(r.base + "/" + id).header("Authorization", token(owner))).andReturn();
    assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);
    return readTree(result);
  }

  private JsonNode readTree(MvcResult result) throws Exception {
    return objectMapper.readTree(body(result));
  }

  private static String body(MvcResult result) throws Exception {
    return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
  }

  private String token(long user) {
    return "Bearer " + jwt.generateAccessToken(user, "u" + user, DEFAULT_TEST_TENANT_ID);
  }
}
