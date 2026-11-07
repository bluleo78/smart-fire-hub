package com.smartfirehub.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.exception.EditConflictException;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.dto.CreatePipelineRequest;
import com.smartfirehub.pipeline.dto.UpdateTriggerRequest;
import com.smartfirehub.pipeline.repository.TriggerRepository;
import com.smartfirehub.pipeline.service.TriggerSchedulerService;
import com.smartfirehub.pipeline.service.TriggerService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.support.TestUsers;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 파이프라인 트리거 편집 낙관적 잠금(WD-576) — 파이프라인(WD-353)과 같은 장치를 트리거 수정 API 에 붙인 것을 HTTP 왕복으로 검증한다.
 *
 * <p>결함: {@code PUT /api/v1/pipelines/{id}/triggers/{triggerId}} 에 편집 충돌 보호가 없어, A 가 cron 을 09:00
 * 으로 저장한 뒤 옛 화면의 B 가 이름만 바꿔 저장하면(옛 cron 포함) 둘 다 204 이고 A 의 변경이 경고 없이 사라졌다. 이제 웹이 트리거 응답의 {@code
 * updatedAt} 을 {@code expectedUpdatedAt} 으로 보내고, 다르면 409 EDIT_CONFLICT 로 거부한다.
 *
 * <p>MockMvc 로 하는 이유: 응답의 {@code updatedAt} 문자열을 그대로 되돌려 보냈을 때 같다고 판정되는지(PG 마이크로초 ↔ JSON ↔
 * LocalDateTime 정밀도)가 이 장치의 핵심이다.
 *
 * <p>핵심 함정: 스케줄 발화({@code nextFireTime})·데이터 변경 폴링이 실행 상태({@code trigger_state})를 쓸 때 수정 시각을 바꾸면,
 * 편집 창을 열어 둔 것만으로 거짓 충돌이 난다 — {@link #executionStateUpdates_doNotInvalidateVersion()} 이 지킨다.
 *
 * <p>클래스 레벨 {@code @Transactional} 을 쓰지 않는다 — 요청이 각자 커밋되어야 다음 요청이 바뀐 버전을 본다. 정리는 {@link
 * #tearDown()}.
 */
@AutoConfigureMockMvc
class TriggerEditConflictTest extends IntegrationTestBase {

  private static final String CONFLICT_MESSAGE_PART = "다른 곳에서 변경되었습니다";

  @Autowired private MockMvc mockMvc;
  @Autowired private DSLContext dsl;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JwtTokenProvider jwt;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TriggerRepository triggerRepository;
  @Autowired private TriggerService triggerService;
  @Autowired private TriggerSchedulerService schedulerService;

  private SecurityFixture fx;
  private long editor;
  private long pipelineId;
  private long triggerId;

  @BeforeEach
  void setUp() throws Exception {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, passwordEncoder);
    editor = fx.createUser("wd576-editor-");
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, editor, DEFAULT_TEST_TENANT_ID, "ADMIN");

    MvcResult pipeline =
        mockMvc
            .perform(
                post("/api/v1/pipelines")
                    .header("Authorization", token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            new CreatePipelineRequest(
                                "wd576-" + System.nanoTime(), "WD-576", List.of()))))
            .andReturn();
    assertThat(pipeline.getResponse().getStatus()).isEqualTo(201);
    pipelineId = readTree(pipeline).get("id").asLong();

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", "매일 새벽");
    body.put("triggerType", "SCHEDULE");
    body.put("config", Map.of("cron", "0 0 3 * * *", "timezone", "UTC"));
    MvcResult trigger =
        mockMvc
            .perform(
                post("/api/v1/pipelines/" + pipelineId + "/triggers")
                    .header("Authorization", token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
            .andReturn();
    assertThat(trigger.getResponse().getStatus()).isEqualTo(201);
    triggerId = readTree(trigger).get("id").asLong();
  }

  /** 등록한 스케줄을 내리고(컨텍스트가 캐시돼 다음 테스트에서 발화하지 않게) 파이프라인(트리거는 cascade)·사용자를 지운다. */
  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    schedulerService.unregisterSchedule(triggerId);
    awaitSecurityAudit();
    TenantRlsTestSupport.cleanupAll(
        () ->
            inTenantFixture(
                DEFAULT_TEST_TENANT_ID,
                () -> dsl.execute("delete from pipeline where created_by = ?", editor)),
        () -> fx.deleteUser(editor));
  }

  /** 조회 응답에 수정 시각이 실리고, 그 문자열을 그대로 보내면 저장되며, 저장 뒤 다시 읽은 값으로 이어 저장해도 자기 저장과 충돌하지 않는다. */
  @Test
  void saveWithReadVersion_succeeds_andReReadVersionChainsNextSave() throws Exception {
    String v1 = version();
    assertThat(v1).as("조회 응답에 updatedAt 이 채워진다").isNotBlank();
    assertThat(readTree(listTriggers()).get(0).get("updatedAt").asText())
        .as("목록 응답도 같은 값")
        .isEqualTo(v1);

    assertThat(putTrigger("새 이름", "0 0 3 * * *", v1)).isEqualTo(204);
    String v2 = version();
    assertThat(v2).as("저장하면 버전이 바뀐다").isNotEqualTo(v1);

    assertThat(putTrigger("또 새 이름", "0 0 3 * * *", v2)).isEqualTo(204);
    assertThat(detail().get("name").asText()).isEqualTo("또 새 이름");
  }

  /**
   * 이슈 재현: A 가 cron 을 09:00 으로 저장한 뒤 옛 화면의 B 가 이름만 바꿔(옛 cron 03:00 포함) 저장하면 409 EDIT_CONFLICT 이고, A
   * 의 cron 과 원래 이름이 그대로 남는다.
   */
  @Test
  void staleEditorSave_rejected409_andEarlierCronKept() throws Exception {
    String base = version();

    assertThat(putTrigger("매일 새벽", "0 0 9 * * *", base)).as("A 저장").isEqualTo(204);

    MvcResult stale = putTriggerResult("B 가 바꾼 이름", "0 0 3 * * *", base);
    assertThat(stale.getResponse().getStatus()).isEqualTo(409);
    JsonNode body = readTree(stale);
    assertThat(body.get("code").asText()).isEqualTo(EditConflictException.CODE);
    assertThat(body.get("message").asText()).contains(CONFLICT_MESSAGE_PART);

    JsonNode after = detail();
    assertThat(after.get("config").get("cron").asText()).isEqualTo("0 0 9 * * *");
    assertThat(after.get("name").asText()).isEqualTo("매일 새벽");
  }

  /** 기준 버전 없이 보내면(AI 도구 등 기존 호출부, 「내 변경으로 덮어쓰기」) 비교하지 않고 저장된다 — 하위 호환. */
  @Test
  void saveWithoutVersion_skipsCheck_andOverwrites() throws Exception {
    String base = version();
    assertThat(putTrigger("매일 새벽", "0 0 9 * * *", base)).isEqualTo(204);

    assertThat(putTrigger("덮어쓴 이름", "0 0 4 * * *", null)).isEqualTo(204);
    JsonNode after = detail();
    assertThat(after.get("name").asText()).isEqualTo("덮어쓴 이름");
    assertThat(after.get("config").get("cron").asText()).isEqualTo("0 0 4 * * *");
  }

  /**
   * 핵심 함정: 스케줄 발화(다음 실행 시각 기록)·데이터 변경 폴링(스냅숏 기록)은 실행 상태만 쓴다 — 편집 창을 연 뒤 이런 갱신이 있어도 그 창의 기준 버전은 여전히
   * 유효해야 한다(거짓 충돌 금지).
   */
  @Test
  void executionStateUpdates_doNotInvalidateVersion() throws Exception {
    String base = version();

    // MockMvc 요청이 같은 스레드의 테넌트 컨텍스트를 지우고 나가므로 직접 호출 전에 다시 세운다(없으면 RLS 가 행을 숨긴다).
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    // 스케줄 발화가 남기는 다음 실행 시각(afterCommit 경로와 같은 저장소 메서드).
    triggerRepository.mergeNextFireTime(triggerId, "2099-01-01T03:00:00Z");
    // 데이터 변경 폴링이 남기는 실행 상태(폴러와 같은 저장소 메서드).
    Map<String, Object> state =
        new HashMap<>(triggerService.getTriggerById(triggerId).triggerState());
    state.put("lastCheckedAt", LocalDateTime.now().toString());
    triggerRepository.updateTriggerState(triggerId, state);

    JsonNode afterState = detail();
    assertThat(afterState.get("nextFireTime").asText())
        .as("실행 상태는 실제로 저장됐다(단언이 공허하지 않게)")
        .isEqualTo("2099-01-01T03:00:00Z");
    assertThat(afterState.get("updatedAt").asText()).as("실행 상태 갱신은 수정 시각을 바꾸지 않는다").isEqualTo(base);
    assertThat(putTrigger("편집한 이름", "0 0 3 * * *", base)).isEqualTo(204);
    assertThat(detail().get("name").asText()).isEqualTo("편집한 이름");
  }

  /** 켜기/끄기는 편집 창이 보여 주는 값을 바꾸는 진짜 변경이다 — 그 뒤 옛 기준으로 저장하면 409(꺼 둔 트리거를 옛 화면이 몰래 되살리지 못한다). */
  @Test
  void toggleAfterRead_makesOldVersionStale() throws Exception {
    String base = version();

    int toggled =
        mockMvc
            .perform(
                patch("/api/v1/pipelines/" + pipelineId + "/triggers/" + triggerId + "/toggle")
                    .header("Authorization", token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"enabled\":false}"))
            .andReturn()
            .getResponse()
            .getStatus();
    assertThat(toggled).isEqualTo(204);

    assertThat(putTrigger("옛 화면 이름", "0 0 3 * * *", base)).isEqualTo(409);
    assertThat(detail().get("name").asText()).isEqualTo("매일 새벽");
  }

  /** 충돌 검사가 다른 검증보다 먼저다 — 낡은 화면에서 저장하면 낡은 내용으로 계산한 검증 오류(잘못된 cron 400) 대신 "다른 곳에서 바뀌었다"를 먼저 알린다. */
  @Test
  void staleVersion_reportedBeforeValidationError() throws Exception {
    String base = version();
    assertThat(putTrigger("매일 새벽", "0 0 9 * * *", base)).isEqualTo(204);

    MvcResult stale = putTriggerResult("매일 새벽", "잘못된 cron", base);
    assertThat(stale.getResponse().getStatus()).isEqualTo(409);
    assertThat(readTree(stale).get("code").asText()).isEqualTo(EditConflictException.CODE);
  }

  /**
   * 비교와 저장 사이에 끼어들 틈이 없다 — 같은 기준 버전으로 동시에 들어온 저장 여러 건 중 하나만 성공한다(행 잠금이 트랜잭션 끝까지 유지되어 뒤 요청은 앞 커밋 뒤의
   * 새 버전을 읽는다).
   */
  @Test
  void concurrentSavesWithSameVersion_onlyOneSucceeds() throws Exception {
    // setUp 의 MockMvc 요청이 이 스레드의 테넌트 컨텍스트를 지웠다 — 직접 서비스 호출 전에 다시 세운다.
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    LocalDateTime base = triggerService.getTriggerById(triggerId).updatedAt();
    int n = 4;
    CyclicBarrier barrier = new CyclicBarrier(n);
    ExecutorService pool = Executors.newFixedThreadPool(n);
    List<Future<Boolean>> results = new ArrayList<>();
    try {
      for (int i = 0; i < n; i++) {
        String name = "동시 저장 " + i;
        results.add(
            pool.submit(
                () -> {
                  barrier.await(10, TimeUnit.SECONDS);
                  return TenantContext.runScopedGet(
                      DEFAULT_TEST_TENANT_ID,
                      () -> {
                        try {
                          triggerService.updateTrigger(
                              pipelineId,
                              triggerId,
                              new UpdateTriggerRequest(name, null, null, null, base),
                              editor);
                          return true;
                        } catch (EditConflictException e) {
                          return false;
                        }
                      });
                }));
      }
      int succeeded = 0;
      for (Future<Boolean> f : results) {
        if (f.get(30, TimeUnit.SECONDS)) {
          succeeded++;
        }
      }
      assertThat(succeeded).as("같은 기준으로 동시에 저장하면 하나만 성공").isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * 켜기도 수정과 같은 행 잠금을 먼저 잡는다 — 다른 트랜잭션이 트리거를 잠그고 config 를 고치는 중이면, 켜기는 그 커밋을 기다렸다가 바뀐 config 로 검증한다.
   * 잠금이 없으면 켜기가 옛(올바른) config 로 검증을 통과한 뒤 동시 수정이 넣은 잘못된 cron 위에 켜짐을 저장한다.
   */
  @Test
  void toggleWaitsForConcurrentEdit_andValidatesEditedConfig() throws Exception {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    triggerService.toggleTrigger(triggerId, false);

    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      // 동시 편집자 흉내(픽스처) — 행을 잠근 채 기다렸다가 검증을 거치지 않은 잘못된 cron 을 저장하고 커밋한다.
      Future<?> editTx =
          pool.submit(
              () ->
                  inTenantFixture(
                      () -> {
                        triggerRepository.lockUpdatedAtById(triggerId);
                        locked.countDown();
                        try {
                          release.await(30, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                        }
                        triggerRepository.update(
                            triggerId,
                            new UpdateTriggerRequest(
                                null, null, null, Map.of("cron", "잘못된 cron", "timezone", "UTC")),
                            this.editor);
                      }));
      assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();

      Future<String> toggle =
          pool.submit(
              () ->
                  TenantContext.runScopedGet(
                      DEFAULT_TEST_TENANT_ID,
                      () -> {
                        try {
                          triggerService.toggleTrigger(triggerId, true);
                          return "enabled";
                        } catch (IllegalArgumentException e) {
                          return "rejected";
                        }
                      }));
      Thread.sleep(500);
      assertThat(toggle.isDone()).as("켜기는 편집자의 행 잠금이 풀릴 때까지 기다린다").isFalse();

      release.countDown();
      editTx.get(30, TimeUnit.SECONDS);
      assertThat(toggle.get(30, TimeUnit.SECONDS))
          .as("켜기는 커밋된 (잘못된) config 로 검증해 거부한다")
          .isEqualTo("rejected");
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
    assertThat(detail().get("isEnabled").asBoolean()).isFalse();
  }

  // ── 헬퍼 ──────────────────────────────────────────────────────────────────

  /** 트리거 단건 조회 응답의 updatedAt 문자열 — 웹이 편집 기준으로 보내는 값 그대로. */
  private String version() throws Exception {
    JsonNode updatedAt = detail().get("updatedAt");
    return updatedAt == null || updatedAt.isNull() ? null : updatedAt.asText();
  }

  private int putTrigger(String name, String cron, String expectedUpdatedAt) throws Exception {
    return putTriggerResult(name, cron, expectedUpdatedAt).getResponse().getStatus();
  }

  /** 웹 편집 창처럼 이름·설명·config 전체를 보내는 수정 요청 — expectedUpdatedAt 은 조회 응답 문자열 그대로(null 이면 뺀다). */
  private MvcResult putTriggerResult(String name, String cron, String expectedUpdatedAt)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", name);
    body.put("description", "");
    body.put("config", Map.of("cron", cron, "timezone", "UTC", "concurrencyPolicy", "SKIP"));
    if (expectedUpdatedAt != null) {
      body.put("expectedUpdatedAt", expectedUpdatedAt);
    }
    return mockMvc
        .perform(
            put("/api/v1/pipelines/" + pipelineId + "/triggers/" + triggerId)
                .header("Authorization", token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andReturn();
  }

  private JsonNode detail() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                get("/api/v1/pipelines/" + pipelineId + "/triggers/" + triggerId)
                    .header("Authorization", token()))
            .andReturn();
    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    return readTree(result);
  }

  private MvcResult listTriggers() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                get("/api/v1/pipelines/" + pipelineId + "/triggers")
                    .header("Authorization", token()))
            .andReturn();
    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    return result;
  }

  private JsonNode readTree(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  private String token() {
    return "Bearer " + jwt.generateAccessToken(editor, "u" + editor, DEFAULT_TEST_TENANT_ID);
  }
}
