package com.smartfirehub.securitylevel;

import static com.smartfirehub.jooq.Tables.AUDIT_LOG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Task 3 A1~A4: 홈 대시보드(/dashboard/stats·attention·activity·health)는 조회자가 볼 수 없는 데이터셋의 이름·항목·개수를 싣지
 * 않는다.
 *
 * <p>모든 단언은 같은 픽스처에 대해 "못 보는 사용자(공개 자격)는 없음 / 볼 수 있는 사용자(민감 자격)는 있음" 쌍으로 둔다 — 양성 대조가 없으면 픽스처가 애초에
 * 응답에 안 잡혀(LIMIT·공유 DB 누적) 통과하는 공허한 테스트가 된다. 개수는 공유 DB 라 절대값이 아니라 숨김 데이터셋 1개 추가 전후의 차이로 단언한다.
 */
@AutoConfigureMockMvc
class HomeDatasetVisibilityTest extends IntegrationTestBase {

  @Autowired private MockMvc mockMvc;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private JwtTokenProvider jwt;
  @Autowired private ObjectMapper om;
  @Autowired private com.smartfirehub.proactive.service.ProactiveContextCollector contextCollector;

  private SecurityFixture fx;
  private final List<Long> users = new ArrayList<>();
  private final List<Long> roles = new ArrayList<>();
  private final List<Long> datasets = new ArrayList<>();
  private String m;
  private long creator;
  private long hiddenId;
  private long visibleId;
  private long lowUser;
  private long highUser;
  private String lowToken;
  private String highToken;

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    m = "hv" + System.nanoTime();
    creator = fx.createUser("hv_creator");
    users.add(creator);
    hiddenId = dataset(m + "_hidden", "민감");
    visibleId = dataset(m + "_visible", "공개");
    lowUser = userAt("공개");
    highUser = userAt("민감");
    lowToken = token(lowUser);
    highToken = token(highUser);
    // 최근 임포트 성공·실패 + 데이터셋 생성 이벤트 — 두 데이터셋 모두. 지금 시각으로 넣어 홈 상위 목록(LIMIT 5)에 든다.
    for (long id : List.of(hiddenId, visibleId)) {
      audit("IMPORT", id, "SUCCESS");
      audit("IMPORT", id, "FAILURE");
      audit("CREATE", id, "SUCCESS");
    }
  }

  private long dataset(String table, String level) {
    long id = fx.createDatasetRow(table, fx.levelId(level), creator);
    datasets.add(id);
    return id;
  }

  /** 기본 USER 역할을 떼고 지정 등급 자격 + dataset:read 만 가진 사용자. */
  private long userAt(String level) {
    long uid = fx.createUser("hv_u");
    users.add(uid);
    fx.removeUserRole(uid);
    long rid = fx.createRole("hv_r_" + System.nanoTime(), fx.levelId(level), "dataset:read");
    roles.add(rid);
    fx.assignRole(uid, rid);
    return uid;
  }

  private String token(long uid) {
    return "Bearer " + jwt.generateAccessToken(uid, "hv" + uid, DEFAULT_TEST_TENANT_ID);
  }

  /** creator 명의 감사 로그 — fx.deleteUser 가 user_id 로 정리한다. */
  private void audit(String action, long datasetId, String result) {
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.insertInto(AUDIT_LOG)
                .set(AUDIT_LOG.USER_ID, creator)
                .set(AUDIT_LOG.USERNAME, "hv")
                .set(AUDIT_LOG.ACTION_TYPE, action)
                .set(AUDIT_LOG.RESOURCE, "dataset")
                .set(AUDIT_LOG.RESOURCE_ID, String.valueOf(datasetId))
                .set(AUDIT_LOG.RESULT, result)
                .set(AUDIT_LOG.ACTION_TIME, LocalDateTime.now())
                .execute());
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    datasets.forEach(fx::deleteDatasetRow); // dataset.created_by FK 때문에 사용자보다 먼저
    users.forEach(fx::deleteUser); // creator 의 감사 로그 포함
    roles.forEach(fx::deleteRole);
  }

  private JsonNode getJson(String url, String token) throws Exception {
    return om.readTree(
        mockMvc
            .perform(get(url).header("Authorization", token))
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private static List<String> texts(JsonNode array, String field) {
    List<String> out = new ArrayList<>();
    array.forEach(n -> out.add(n.path(field).asText()));
    return out;
  }

  /** 데이터셋 항목(entityType=DATASET)의 entityId 목록. */
  private static List<Long> datasetEntityIds(JsonNode array) {
    List<Long> out = new ArrayList<>();
    array.forEach(
        n -> {
          if ("DATASET".equals(n.path("entityType").asText())) {
            out.add(n.path("entityId").asLong());
          }
        });
    return out;
  }

  @Test
  void stats_recentImports_excludeHiddenDatasetName() throws Exception {
    List<String> low =
        texts(getJson("/api/v1/dashboard/stats", lowToken).get("recentImports"), "datasetName");
    List<String> high =
        texts(getJson("/api/v1/dashboard/stats", highToken).get("recentImports"), "datasetName");
    assertThat(low).contains("sf_" + m + "_visible").doesNotContain("sf_" + m + "_hidden");
    assertThat(high).contains("sf_" + m + "_hidden");
  }

  @Test
  void attention_importFailure_excludesHiddenDataset() throws Exception {
    List<Long> low = datasetEntityIds(getJson("/api/v1/dashboard/attention", lowToken));
    List<Long> high = datasetEntityIds(getJson("/api/v1/dashboard/attention", highToken));
    assertThat(low).contains(visibleId).doesNotContain(hiddenId);
    assertThat(high).contains(hiddenId);
  }

  @Test
  void activity_excludesHiddenDatasetEvents_withoutIdFallbackTitle() throws Exception {
    String url = "/api/v1/dashboard/activity?type=DATASET&size=100";
    JsonNode low = getJson(url, lowToken).get("items");
    JsonNode high = getJson(url, highToken).get("items");
    assertThat(datasetEntityIds(low)).contains(visibleId).doesNotContain(hiddenId);
    // 숨김 행이 이름 대신 "dataset #id" 로 남는 형태(ON 조건에 가시성을 둔 경우)도 막는다.
    assertThat(texts(low, "title"))
        .noneMatch(t -> t.contains("sf_" + m + "_hidden") || t.contains("#" + hiddenId));
    assertThat(datasetEntityIds(high)).contains(hiddenId);
  }

  @Test
  void counts_stats_and_health_countOnlyVisibleDatasets() throws Exception {
    long lowStats = getJson("/api/v1/dashboard/stats", lowToken).get("totalDatasets").asLong();
    long highStats = getJson("/api/v1/dashboard/stats", highToken).get("totalDatasets").asLong();
    long lowSource = getJson("/api/v1/dashboard/stats", lowToken).get("sourceDatasets").asLong();
    long highSource = getJson("/api/v1/dashboard/stats", highToken).get("sourceDatasets").asLong();
    long lowHealth = healthTotal(lowToken);
    long highHealth = healthTotal(highToken);
    long lowFresh = healthFresh(lowToken);
    long highFresh = healthFresh(highToken);

    dataset(m + "_hidden2", "민감"); // 못 보는 사용자에게는 개수가 늘지 않아야 한다

    assertThat(getJson("/api/v1/dashboard/stats", lowToken).get("totalDatasets").asLong())
        .isEqualTo(lowStats);
    assertThat(getJson("/api/v1/dashboard/stats", lowToken).get("sourceDatasets").asLong())
        .isEqualTo(lowSource);
    assertThat(healthTotal(lowToken)).isEqualTo(lowHealth);
    assertThat(healthFresh(lowToken)).isEqualTo(lowFresh);
    // 양성 대조: 볼 수 있는 사용자에게는 1 늘어난다.
    assertThat(getJson("/api/v1/dashboard/stats", highToken).get("totalDatasets").asLong())
        .isEqualTo(highStats + 1);
    assertThat(getJson("/api/v1/dashboard/stats", highToken).get("sourceDatasets").asLong())
        .isEqualTo(highSource + 1);
    assertThat(healthTotal(highToken)).isEqualTo(highHealth + 1);
    assertThat(healthFresh(highToken)).isEqualTo(highFresh + 1);
  }

  private long healthTotal(String token) throws Exception {
    return getJson("/api/v1/dashboard/health", token).get("datasetHealth").get("total").asLong();
  }

  /** 24시간 안에 만든(임포트 없는) 원천 데이터셋은 fresh 로 센다 — 숨김 데이터셋이 이 개수로도 드러나면 안 된다. */
  private long healthFresh(String token) throws Exception {
    return getJson("/api/v1/dashboard/health", token).get("datasetHealth").get("fresh").asLong();
  }

  /**
   * 비요청 경로 — proactive 리포트 컨텍스트 수집(비동기 러너)도 같은 홈 데이터를 쓴다. 요청 사용자가 없으므로 작업 소유자 자격으로 거른다: 못 보는 소유자의
   * 리포트 컨텍스트에는 숨김 데이터셋 이름이 없고, 볼 수 있는 소유자에게는 있다(소유자 자격이 아니라 "아무것도 못 봄"으로 돌면 공개 데이터셋도 빠진다 — 그 회귀도 같이
   * 잡는다).
   */
  @Test
  void proactiveContext_filtersByJobOwnerClearance() {
    String lowCtx = contextCollector.collectContext(java.util.Map.of(), null, lowUser);
    String highCtx = contextCollector.collectContext(java.util.Map.of(), null, highUser);
    assertThat(lowCtx).contains("sf_" + m + "_visible").doesNotContain("sf_" + m + "_hidden");
    assertThat(highCtx).contains("sf_" + m + "_hidden");
  }
}
