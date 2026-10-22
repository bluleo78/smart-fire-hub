package com.smartfirehub.securitylevel;

import static com.smartfirehub.jooq.Tables.PIPELINE;
import static com.smartfirehub.jooq.Tables.PIPELINE_TRIGGER;
import static com.smartfirehub.jooq.Tables.TRIGGER_EVENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

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
import org.jooq.JSONB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * WD-161: 데이터 변경 트리거의 조회 응답·이벤트가 조회자가 볼 수 없는(상위 등급) 데이터셋의 행 수·변경 시점을 드러내지 않는다.
 *
 * <p>수정 전에는 {@code triggerState.lastSnapshot}({데이터셋id: 행 수})이 그대로 응답에 실려, 데이터셋 행 수 API 가 404 인 사용자도
 * 숨김 데이터셋의 실제 행 수와 그 변화를 볼 수 있었고, 트리거 이벤트 {@code detail.changedDatasets} 로 언제 바뀌었는지도 드러났다.
 *
 * <p>이벤트 단언은 "못 보는 사용자(공개 자격)는 숨김 id 없음 / 볼 수 있는 사용자(민감 자격)는 있음" 쌍으로 둔다 — 양성 대조가 없으면 픽스처가 응답에 안 잡혀
 * 통과하는 공허한 테스트가 된다.
 */
@AutoConfigureMockMvc
class TriggerSnapshotVisibilityTest extends IntegrationTestBase {

  @Autowired private MockMvc mockMvc;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private JwtTokenProvider jwt;
  @Autowired private ObjectMapper om;

  private SecurityFixture fx;
  private final List<Long> users = new ArrayList<>();
  private final List<Long> roles = new ArrayList<>();
  private final List<Long> datasets = new ArrayList<>();
  private long creator;
  private long hiddenId;
  private long visibleId;
  private long pipelineId;
  private long triggerId;
  private String lowToken;
  private String highToken;

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    String m = "tsv" + System.nanoTime();
    creator = fx.createUser("tsv_creator");
    users.add(creator);
    hiddenId = fx.createDatasetRow(m + "_hidden", fx.levelId("민감"), creator);
    visibleId = fx.createDatasetRow(m + "_visible", fx.levelId("공개"), creator);
    datasets.add(hiddenId);
    datasets.add(visibleId);
    lowToken = token(userAt("공개"));
    highToken = token(userAt("민감"));

    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> {
          pipelineId =
              dsl.insertInto(PIPELINE)
                  .set(PIPELINE.NAME, m)
                  .set(PIPELINE.IS_ACTIVE, true)
                  .set(PIPELINE.CREATED_BY, creator)
                  .returning(PIPELINE.ID)
                  .fetchOne()
                  .getId();
          // 폴러가 남기는 상태 그대로 — 감시 데이터셋별 실제 행 수 + 마지막 확인 시각
          String state =
              "{\"lastSnapshot\":{\""
                  + hiddenId
                  + "\":3,\""
                  + visibleId
                  + "\":2},\"lastCheckedAt\":\"2026-10-22T09:00:00\"}";
          triggerId =
              dsl.insertInto(PIPELINE_TRIGGER)
                  .set(PIPELINE_TRIGGER.PIPELINE_ID, pipelineId)
                  .set(PIPELINE_TRIGGER.TRIGGER_TYPE, "DATASET_CHANGE")
                  .set(PIPELINE_TRIGGER.NAME, "변경 감시")
                  .set(PIPELINE_TRIGGER.IS_ENABLED, true)
                  .set(
                      PIPELINE_TRIGGER.CONFIG,
                      JSONB.valueOf(
                          "{\"datasetIds\":["
                              + hiddenId
                              + ","
                              + visibleId
                              + "],\"pollingIntervalSeconds\":60}"))
                  .set(PIPELINE_TRIGGER.TRIGGER_STATE, JSONB.valueOf(state))
                  .set(PIPELINE_TRIGGER.CREATED_BY, creator)
                  .returning(PIPELINE_TRIGGER.ID)
                  .fetchOne()
                  .getId();
          dsl.insertInto(TRIGGER_EVENT)
              .set(TRIGGER_EVENT.TRIGGER_ID, triggerId)
              .set(TRIGGER_EVENT.PIPELINE_ID, pipelineId)
              .set(TRIGGER_EVENT.EVENT_TYPE, "FIRED")
              .set(
                  TRIGGER_EVENT.DETAIL,
                  JSONB.valueOf("{\"changedDatasets\":[" + hiddenId + "," + visibleId + "]}"))
              .execute();
        });
  }

  /** 기본 USER 역할을 떼고 지정 등급 자격 + 파이프라인·트리거 조회 권한만 가진 사용자. */
  private long userAt(String level) {
    long uid = fx.createUser("tsv_u");
    users.add(uid);
    fx.removeUserRole(uid);
    long rid =
        fx.createRole(
            "tsv_r_" + System.nanoTime(),
            fx.levelId(level),
            "pipeline:read",
            "trigger:read",
            "dataset:read");
    roles.add(rid);
    fx.assignRole(uid, rid);
    return uid;
  }

  private String token(long uid) {
    return "Bearer " + jwt.generateAccessToken(uid, "tsv" + uid, DEFAULT_TEST_TENANT_ID);
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> {
          dsl.deleteFrom(TRIGGER_EVENT).where(TRIGGER_EVENT.PIPELINE_ID.eq(pipelineId)).execute();
          dsl.deleteFrom(PIPELINE_TRIGGER)
              .where(PIPELINE_TRIGGER.PIPELINE_ID.eq(pipelineId))
              .execute();
          dsl.deleteFrom(PIPELINE).where(PIPELINE.ID.eq(pipelineId)).execute();
        });
    datasets.forEach(fx::deleteDatasetRow);
    users.forEach(fx::deleteUser);
    roles.forEach(fx::deleteRole);
  }

  private JsonNode getJson(String url, String token) throws Exception {
    var res = mockMvc.perform(get(url).header("Authorization", token)).andReturn().getResponse();
    assertThat(res.getStatus()).as(url).isEqualTo(200);
    return om.readTree(res.getContentAsString());
  }

  private static List<Long> changedDatasets(JsonNode events) {
    List<Long> out = new ArrayList<>();
    events.forEach(e -> e.path("detail").path("changedDatasets").forEach(n -> out.add(n.asLong())));
    return out;
  }

  @Test
  void triggerList_and_detail_omitPollRowCountSnapshot() throws Exception {
    for (String token : List.of(lowToken, highToken)) {
      JsonNode list = getJson("/api/v1/pipelines/" + pipelineId + "/triggers", token);
      assertThat(list).hasSize(1);
      JsonNode state = list.get(0).path("triggerState");
      // 숨김 데이터셋 행 수(3)가 담긴 내부 스냅숏은 누구에게도 나가지 않는다
      assertThat(state.has("lastSnapshot")).isFalse();
      // 나머지 상태는 그대로 — 스냅숏만 뺐다는 대조
      assertThat(state.path("lastCheckedAt").asText()).isEqualTo("2026-10-22T09:00:00");

      JsonNode one = getJson("/api/v1/pipelines/" + pipelineId + "/triggers/" + triggerId, token);
      assertThat(one.path("triggerState").has("lastSnapshot")).isFalse();
    }
  }

  @Test
  void triggerEvents_hideChangedDatasetsTheViewerCannotSee() throws Exception {
    // 두 조회 경로(트리거 하위·파이프라인 하위) 모두
    for (String url :
        List.of(
            "/api/v1/pipelines/" + pipelineId + "/triggers/events",
            "/api/v1/pipelines/" + pipelineId + "/trigger-events")) {
      assertThat(changedDatasets(getJson(url, lowToken))).as(url).containsExactly(visibleId);
      // 양성 대조: 민감 자격은 둘 다 본다 — 픽스처가 응답에 잡힌다는 근거
      assertThat(changedDatasets(getJson(url, highToken)))
          .as(url)
          .containsExactlyInAnyOrder(hiddenId, visibleId);
    }
  }
}
