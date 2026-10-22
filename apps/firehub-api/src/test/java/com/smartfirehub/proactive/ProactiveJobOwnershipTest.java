package com.smartfirehub.proactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.proactive.service.ProactiveJobAsyncRunner;
import com.smartfirehub.proactive.service.ProactiveJobSchedulerService;
import com.smartfirehub.proactive.service.ProactiveJobService;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * WD-164·WD-165: 남의 스마트 작업이나 없는 작업에 대한 실행·수정·삭제는 조회와 같은 404 로 거절하고, 주인의 작업·실행 이력·예약 실행에 흔적을 남기지
 * 않는다.
 *
 * <p>WD-164: 남의 스마트 작업이나 없는 작업으로 「지금 실행」을 보내면 조회와 같은 404 로 거절하고, 주인의 실행 이력·실행 슬롯에 흔적을 남기지 않는다.
 *
 * <p>수정 전에는 소유자 확인이 비동기 실행 안(실행 행을 만든 뒤)에만 있어 202 가 나갔고, 주인 작업에 「Job을 찾을 수 없습니다」 FAILED 실행이 쌓였다.
 * 비동기 실행기는 목으로 바꿔 "실행으로 넘어갔는가"를 직접 본다 — 주인의 실행은 넘어간다(양성 대조), 남의 실행은 넘어가지 않는다.
 */
@AutoConfigureMockMvc
class ProactiveJobOwnershipTest extends IntegrationTestBase {

  @Autowired private MockMvc mockMvc;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private JwtTokenProvider jwt;
  @Autowired private ObjectMapper om;
  @Autowired private ProactiveJobService proactiveJobService;
  @MockitoBean private ProactiveJobAsyncRunner asyncRunner;
  // 예약 실행 등록·해제를 직접 본다(WD-165 — 남의 삭제 요청이 주인 작업의 예약 실행을 해제하지 않는지)
  @MockitoBean private ProactiveJobSchedulerService schedulerService;

  private SecurityFixture fx;
  private final List<Long> users = new ArrayList<>();
  private final List<Long> roles = new ArrayList<>();
  private long owner;
  private String ownerToken;
  private String otherToken;
  private long jobId;

  @BeforeEach
  void setUp() throws Exception {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    owner = user();
    ownerToken = token(owner);
    otherToken = token(user());
    MockHttpServletResponse res =
        mockMvc
            .perform(
                post("/api/v1/proactive/jobs")
                    .header("Authorization", ownerToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"name\":\"주인_작업\",\"prompt\":\"요약\",\"enabled\":false,\"config\":{}}"))
            .andReturn()
            .getResponse();
    assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(201);
    jobId = om.readTree(res.getContentAsString()).path("id").asLong();
  }

  /** 스마트 작업 읽기·쓰기 권한만 가진 사용자(기본 USER 역할은 뗀다). */
  private long user() {
    long uid = fx.createUser("pjx_u");
    users.add(uid);
    fx.removeUserRole(uid);
    long rid =
        fx.createRole(
            "pjx_r_" + System.nanoTime(), fx.levelId("공개"), "proactive:read", "proactive:write");
    roles.add(rid);
    fx.assignRole(uid, rid);
    return uid;
  }

  private String token(long uid) {
    return "Bearer " + jwt.generateAccessToken(uid, "pjx" + uid, DEFAULT_TEST_TENANT_ID);
  }

  @AfterEach
  void tearDown() {
    proactiveJobService.releaseRunSlot(jobId);
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.deleteFrom(table(name("proactive_job")))
                .where(field(name("user_id"), Long.class).in(users))
                .execute());
    users.forEach(fx::deleteUser);
    roles.forEach(fx::deleteRole);
  }

  private int execute(long id, String token) throws Exception {
    return mockMvc
        .perform(post("/api/v1/proactive/jobs/" + id + "/execute").header("Authorization", token))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  private int executionRows() {
    return TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.fetchCount(
                table(name("proactive_job_execution")),
                field(name("job_id"), Long.class).eq(jobId)));
  }

  @Test
  void executeSomeoneElsesOrMissingJob_is404_andLeavesNoTrace() throws Exception {
    // 남의 작업 · 없는 작업 → 조회와 같은 404
    assertThat(execute(jobId, otherToken)).isEqualTo(404);
    assertThat(execute(99_999_999L, otherToken)).isEqualTo(404);
    verify(asyncRunner, never()).executeJob(anyLong(), anyLong());
    assertThat(executionRows()).isZero();

    // 양성 대조: 주인은 곧바로 실행된다 — 남의 시도가 실행 슬롯을 잡아 409 로 막지 않았다는 뜻이기도 하다
    assertThat(execute(jobId, ownerToken)).isEqualTo(202);
    verify(asyncRunner, times(1)).executeJob(eq(jobId), eq(owner));
  }

  private int send(MockHttpServletRequestBuilder req, String token, String body) throws Exception {
    req.header("Authorization", token);
    if (body != null) {
      req.contentType(MediaType.APPLICATION_JSON).content(body);
    }
    return mockMvc.perform(req).andReturn().getResponse().getStatus();
  }

  private String ownerJobName() throws Exception {
    return om.readTree(
            mockMvc
                .perform(get("/api/v1/proactive/jobs/" + jobId).header("Authorization", ownerToken))
                .andReturn()
                .getResponse()
                .getContentAsString())
        .path("name")
        .asText();
  }

  @Test
  void updateOrDeleteSomeoneElsesOrMissingJob_is404_andLeavesOwnerJobIntact() throws Exception {
    String url = "/api/v1/proactive/jobs/" + jobId;
    String rename = "{\"name\":\"덮어쓰기\"}";
    // 남의 작업 · 없는 작업 → 수정·삭제 모두 404 (예전엔 204 무동작)
    assertThat(send(put(url), otherToken, rename)).isEqualTo(404);
    assertThat(send(delete(url), otherToken, null)).isEqualTo(404);
    assertThat(send(put("/api/v1/proactive/jobs/99999999"), otherToken, rename)).isEqualTo(404);
    assertThat(send(delete("/api/v1/proactive/jobs/99999999"), otherToken, null)).isEqualTo(404);
    // 주인 작업은 그대로이고, 남의 삭제 요청이 주인 작업의 예약 실행을 해제하지 않았다
    assertThat(ownerJobName()).isEqualTo("주인_작업");
    verify(schedulerService, never()).unregisterSchedule(jobId);

    // 양성 대조: 주인은 수정·삭제할 수 있다
    assertThat(send(put(url), ownerToken, rename)).isEqualTo(204);
    assertThat(ownerJobName()).isEqualTo("덮어쓰기");
    assertThat(send(delete(url), ownerToken, null)).isEqualTo(204);
    verify(schedulerService, times(1)).unregisterSchedule(jobId);
  }
}
