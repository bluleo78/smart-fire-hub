package com.smartfirehub.securitylevel.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 스펙 §2.5 "볼 수 없는 데이터셋은 존재 자체를 숨긴다"를 <b>모든</b> 데이터셋 ID 라우트에서 고정한다.
 *
 * <p>왜 열거하는가: 경로를 손으로 나열하면 새 엔드포인트가 생길 때 빠진다. 핸들러 매핑을 순회하면 새 라우트가 자동으로 검사 대상이 된다. 사용자는 모든 권한을 가졌지만
 * 자격은 '공개' — 그래서 404 가 아닌 응답은 전부 가드 누락이다.
 *
 * <p>변이 확인: WebMvcConfig 에서 datasetAccessInterceptor 등록 한 줄을 지우면 이 테스트가 수십 건의 실패 목록과 함께 FAIL
 * 한다(Step 6).
 */
@AutoConfigureMockMvc
class DatasetRouteHidingTest extends IntegrationTestBase {

  @Autowired private MockMvc mockMvc;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private JwtTokenProvider jwt;
  @Autowired private ObjectMapper objectMapper;

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  private RequestMappingHandlerMapping handlerMapping;

  private SecurityFixture fx;
  private long creatorId;
  private long userId;
  private long roleId;
  private long hiddenId;
  private long visibleId;
  private String token;
  private String visibleTable;

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    creatorId = fx.createUser("rh_creator");
    userId = fx.createUser("rh_user");
    fx.removeUserRole(userId);
    roleId = fx.createAllPermissionRole("rh_all_" + System.nanoTime(), fx.levelId("공개"));
    fx.assignRole(userId, roleId);
    hiddenId = fx.createDatasetRow("rh_hidden_" + System.nanoTime(), fx.levelId("민감"), creatorId);
    // 양성 대조(200)가 성립하려면 상세 조회가 읽는 물리 테이블이 있어야 한다 — 메타 행만으론 500 이 난다.
    visibleTable = "rh_visible_" + System.nanoTime();
    visibleId = fx.createDatasetRow(visibleTable, fx.levelId("공개"), creatorId);
    dsl.execute("CREATE TABLE IF NOT EXISTS \"data\".\"" + visibleTable + "\" (id bigserial)");
    token = "Bearer " + jwt.generateAccessToken(userId, "rh" + userId, DEFAULT_TEST_TENANT_ID);
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    fx.deleteDatasetRow(hiddenId);
    fx.deleteDatasetRow(visibleId);
    dsl.execute("DROP TABLE IF EXISTS \"data\".\"" + visibleTable + "\"");
    fx.deleteUser(userId);
    fx.deleteUser(creatorId);
    fx.deleteRole(roleId);
  }

  @Test
  void everyDatasetIdRoute_returns404ForHiddenDataset() throws Exception {
    List<String> failures = new ArrayList<>();
    int checked = 0;
    List<String> enumerated = new ArrayList<>();
    for (var entry : handlerMapping.getHandlerMethods().entrySet()) {
      var info = entry.getKey();
      if (info.getPathPatternsCondition() == null) {
        continue;
      }
      for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
        if (!pattern.startsWith("/api/v1/datasets/")) {
          continue;
        }
        // 첫 경로 세그먼트가 변수라면 인터셉터가 읽는 이름(id/datasetId)이어야 한다. 다른 이름(예: {dsId})은
        // 인터셉터가 건너뛰어 가드가 빠지므로, 열거가 같은 규칙으로 거르면 아무도 못 잡는다 — 여기서 위반으로 기록한다.
        String firstSegment = pattern.substring("/api/v1/datasets/".length()).split("/")[0];
        if (firstSegment.startsWith("{")
            && !firstSegment.equals("{id}")
            && !firstSegment.equals("{datasetId}")) {
          failures.add("인터셉터가 읽지 않는 경로 변수명: " + pattern);
          continue;
        }
        if (!(pattern.contains("{id}") || pattern.contains("{datasetId}"))) {
          continue;
        }
        String url =
            pattern
                .replace("{id}", String.valueOf(hiddenId))
                .replace("{datasetId}", String.valueOf(hiddenId))
                .replaceAll("\\{[^}]+}", "1");
        Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
        // consumes=multipart 라우트(업로드)는 JSON 으로 보내면 핸들러 매칭 전에 415 가 난다 — multipart 빌더로 보낸다.
        boolean multipartRoute =
            info.getConsumesCondition().getConsumableMediaTypes().stream()
                .anyMatch(mt -> mt.isCompatibleWith(MediaType.MULTIPART_FORM_DATA));
        for (RequestMethod m : methods.isEmpty() ? Set.of(RequestMethod.GET) : methods) {
          var builder =
              multipartRoute
                  ? multipart(HttpMethod.valueOf(m.name()), url).header("Authorization", token)
                  : request(HttpMethod.valueOf(m.name()), url)
                      .header("Authorization", token)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{}");
          var response = mockMvc.perform(builder).andReturn().getResponse();
          int status = response.getStatus();
          checked++;
          enumerated.add(m + " " + pattern);
          // 보조 변수(columnId 등)는 1 이라 엔티티 부재로도 404 가 날 수 있다 — 본문 메시지가 '데이터셋' 부재(가드가 만든
          // 것)임을 확인해야 가드 증거가 된다.
          String expected = "Dataset not found: " + hiddenId;
          String message = "";
          try {
            JsonNode n = objectMapper.readTree(response.getContentAsString());
            message = n.has("message") ? n.get("message").asText() : "";
          } catch (Exception ignored) {
            // 본문이 JSON 이 아니면 아래에서 불일치로 기록된다.
          }
          if (status != 404 || !expected.equals(message)) {
            failures.add(m + " " + pattern + " -> " + status + " [" + message + "]");
          }
        }
      }
    }
    assertThat(checked).as("열거된 데이터셋 ID 라우트 수(0 이면 열거가 깨진 것)").isGreaterThan(40);
    assertThat(failures).as("숨김 데이터셋에 404 가 아닌 라우트").isEmpty();
  }

  @Test
  void hiddenAndMissing_haveIndistinguishableBodies() throws Exception {
    long missing = 9_000_000_001L;
    JsonNode hidden = body("/api/v1/datasets/" + hiddenId);
    JsonNode none = body("/api/v1/datasets/" + missing);
    assertThat(hidden.get("status").asInt()).isEqualTo(404);
    assertThat(hidden.get("message").asText()).isEqualTo("Dataset not found: " + hiddenId);
    assertThat(none.get("message").asText()).isEqualTo("Dataset not found: " + missing);
    assertThat(hidden.get("error").asText()).isEqualTo(none.get("error").asText());
    assertThat(hidden.has("code")).isEqualTo(none.has("code"));
  }

  @Test
  void visibleDataset_stillReachable_positiveControl() throws Exception {
    int status =
        mockMvc
            .perform(get("/api/v1/datasets/" + visibleId).header("Authorization", token))
            .andReturn()
            .getResponse()
            .getStatus();
    assertThat(status).isEqualTo(200);
  }

  @Test
  void unauthenticated_keepsExisting401_notServerError() throws Exception {
    int status =
        mockMvc.perform(get("/api/v1/datasets/" + hiddenId)).andReturn().getResponse().getStatus();
    assertThat(status).isEqualTo(401);
  }

  private JsonNode body(String url) throws Exception {
    String json =
        mockMvc
            .perform(get(url).header("Authorization", token))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(json);
  }
}
