package com.smartfirehub.proactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * WD-163: 리포트 양식은 목록과 같은 범위(기본 양식 + 본인 양식)에서만 상세 조회·작업 연결이 된다.
 *
 * <p>수정 전에는 목록만 소유자로 거르고 상세 조회({@code GET /proactive/templates/{id}})와 작업 생성·수정의 {@code templateId}
 * 는 id 로만 찾아, 양식 번호만 알면 남의 비공개 양식(섹션 지시문 전체)을 읽고 내 스마트 작업에 연결해 쓸 수 있었다.
 *
 * <p>모든 단언은 "주인은 된다 / 남은 안 된다" 쌍으로 둔다 — 주인 쪽 양성 대조가 없으면 픽스처가 잘못돼 둘 다 실패해도 통과하는 공허한 테스트가 된다.
 */
@AutoConfigureMockMvc
class ReportTemplateOwnershipTest extends IntegrationTestBase {

  @Autowired private MockMvc mockMvc;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private JwtTokenProvider jwt;
  @Autowired private ObjectMapper om;

  private SecurityFixture fx;
  private final List<Long> users = new ArrayList<>();
  private final List<Long> roles = new ArrayList<>();
  private String ownerToken;
  private String otherToken;
  private long ownerTemplateId;

  @BeforeEach
  void setUp() throws Exception {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    ownerToken = token(user());
    otherToken = token(user());
    JsonNode created =
        json(
            send(
                post("/api/v1/proactive/templates"),
                ownerToken,
                "{\"name\":\"주인_비공개양식\",\"sections\":[{\"key\":\"s\",\"label\":\"비밀 지시문\"}],"
                    + "\"style\":\"formal\"}"),
            201);
    ownerTemplateId = created.path("id").asLong();
  }

  /** 스마트 작업 읽기·쓰기 권한만 가진 사용자(기본 USER 역할은 뗀다). */
  private long user() {
    long uid = fx.createUser("rto_u");
    users.add(uid);
    fx.removeUserRole(uid);
    long rid =
        fx.createRole(
            "rto_r_" + System.nanoTime(), fx.levelId("공개"), "proactive:read", "proactive:write");
    roles.add(rid);
    fx.assignRole(uid, rid);
    return uid;
  }

  private String token(long uid) {
    return "Bearer " + jwt.generateAccessToken(uid, "rto" + uid, DEFAULT_TEST_TENANT_ID);
  }

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

  private MockHttpServletResponse send(MockHttpServletRequestBuilder req, String token, String body)
      throws Exception {
    req.header("Authorization", token);
    if (body != null) {
      req.contentType(MediaType.APPLICATION_JSON).content(body);
    }
    return mockMvc.perform(req).andReturn().getResponse();
  }

  private JsonNode json(MockHttpServletResponse res, int expectedStatus) throws Exception {
    assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(expectedStatus);
    return om.readTree(res.getContentAsString());
  }

  private String jobBody(long templateId) {
    return "{\"name\":\"rto_job\",\"prompt\":\"요약\",\"templateId\":"
        + templateId
        + ",\"enabled\":false,\"config\":{}}";
  }

  private long builtinTemplateId(String token) throws Exception {
    for (JsonNode t : json(send(get("/api/v1/proactive/templates"), token, null), 200)) {
      if (t.path("builtin").asBoolean()) {
        return t.path("id").asLong();
      }
    }
    throw new AssertionError("기본 양식이 없다");
  }

  @Test
  void templateDetail_isVisibleOnlyToOwner_andBuiltinToEveryone() throws Exception {
    String url = "/api/v1/proactive/templates/" + ownerTemplateId;
    // 주인은 본다(양성 대조)
    assertThat(json(send(get(url), ownerToken, null), 200).path("name").asText())
        .isEqualTo("주인_비공개양식");
    // 남은 없는 양식과 같은 404 — 지시문이 응답에 실리지 않는다
    MockHttpServletResponse other = send(get(url), otherToken, null);
    assertThat(other.getStatus()).isEqualTo(404);
    assertThat(other.getContentAsString()).doesNotContain("비밀 지시문");
    // 기본 양식은 누구나 상세를 본다
    long builtin = builtinTemplateId(otherToken);
    json(send(get("/api/v1/proactive/templates/" + builtin), otherToken, null), 200);
  }

  @Test
  void job_cannotLinkSomeoneElsesTemplate_onCreateOrUpdate() throws Exception {
    // 주인은 자기 양식으로 작업을 만든다(양성 대조)
    json(send(post("/api/v1/proactive/jobs"), ownerToken, jobBody(ownerTemplateId)), 201);

    // 남은 그 양식으로 만들 수 없다
    MockHttpServletResponse denied =
        send(post("/api/v1/proactive/jobs"), otherToken, jobBody(ownerTemplateId));
    assertThat(denied.getStatus()).isEqualTo(400);
    assertThat(denied.getContentAsString()).contains("리포트 양식을 찾을 수 없습니다");

    // 남은 기본 양식으로 만든 뒤, 수정으로 주인 양식을 끼워 넣을 수도 없다
    long builtin = builtinTemplateId(otherToken);
    long jobId =
        json(send(post("/api/v1/proactive/jobs"), otherToken, jobBody(builtin)), 201)
            .path("id")
            .asLong();
    String jobUrl = "/api/v1/proactive/jobs/" + jobId;
    assertThat(send(put(jobUrl), otherToken, jobBody(ownerTemplateId)).getStatus()).isEqualTo(400);
    assertThat(json(send(get(jobUrl), otherToken, null), 200).path("templateId").asLong())
        .isEqualTo(builtin);
  }

  @Test
  void templateUpdateAndDelete_bySomeoneElse_are404_andLeaveTemplateIntact() throws Exception {
    String url = "/api/v1/proactive/templates/" + ownerTemplateId;
    String body = "{\"name\":\"덮어쓰기\",\"sections\":[{\"key\":\"x\",\"label\":\"x\"}]}";
    assertThat(send(put(url), otherToken, body).getStatus()).isEqualTo(404);
    assertThat(send(delete(url), otherToken, null).getStatus()).isEqualTo(404);
    // 주인 양식은 그대로다
    assertThat(json(send(get(url), ownerToken, null), 200).path("name").asText())
        .isEqualTo("주인_비공개양식");
  }

  /**
   * WD-298: API 로 보낸 깨진 섹션(중복 key·알 수 없는 type·빈 label)은 400 과 한국어 사유로 거절된다. 남의 양식에 보낸 깨진 수정은 검증보다
   * 소유 확인이 먼저라 그대로 404 다 — 400 으로 답하면 양식이 있다는 것이 드러난다.
   */
  @Test
  void invalidSections_are400WithKoreanReason_butSomeoneElsesTemplateStays404() throws Exception {
    String badBody =
        "{\"name\":\"깨진 양식\",\"sections\":[{\"key\":\"dup\",\"type\":\"text\",\"label\":\"하나\"},"
            + "{\"key\":\"dup\",\"type\":\"nosuch\",\"label\":\"\"},{\"type\":\"cards\"}]}";
    JsonNode created = json(send(post("/api/v1/proactive/templates"), ownerToken, badBody), 400);
    assertThat(created.path("message").asText()).contains("섹션 Key가 중복되었습니다: \"dup\"");

    String url = "/api/v1/proactive/templates/" + ownerTemplateId;
    String badUpdate =
        "{\"name\":\"주인_비공개양식\",\"sections\":[{\"key\":\"a\",\"type\":\"nosuch\",\"label\":\"첫째\"}]}";
    JsonNode updated = json(send(put(url), ownerToken, badUpdate), 400);
    assertThat(updated.path("message").asText()).contains("타입 \"nosuch\"은(는) 지원하지 않습니다");
    assertThat(send(put(url), otherToken, badUpdate).getStatus()).isEqualTo(404);
    // 거절된 수정은 저장되지 않았다
    assertThat(
            json(send(get(url), ownerToken, null), 200)
                .path("sections")
                .get(0)
                .path("key")
                .asText())
        .isEqualTo("s");
  }
}
