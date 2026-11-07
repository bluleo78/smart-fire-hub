package com.smartfirehub.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetResponse;
import com.smartfirehub.dataset.dto.UpdateDatasetRequest;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.global.exception.EditConflictException;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.support.TestUsers;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
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
 * 데이터셋 정보(이름·설명·카테고리) 수정의 편집 충돌(낙관적 잠금) — WD-575.
 *
 * <p>결함: {@code PUT /api/v1/datasets/{id}} 가 세 칸을 통째로 바꾸는데 기준 버전 비교가 없어, A 가 이름을 바꾼 뒤 옛 화면의 B 가 설명만
 * 고쳐 저장하면 B 의 옛 이름이 다시 써져 A 의 변경이 조용히 사라졌다(둘 다 204). 차트·쿼리·대시보드·파이프라인과 같은 장치를 붙였다 — 웹이 상세의 {@code
 * updatedAt} 을 {@code expectedUpdatedAt} 으로 보내고, 다르면 409 EDIT_CONFLICT 로 거부한다.
 *
 * <p>HTTP 왕복(MockMvc)으로 하는 이유: 상세 응답의 {@code updatedAt} 문자열을 그대로 되돌려 보냈을 때 같다고 판정되는지(PG 마이크로초 ↔
 * JSON ↔ LocalDateTime)가 핵심이라 객체를 직접 넘기는 테스트로는 확인되지 않는다. 동시 저장은 서비스를 두 스레드에서 직접 부른다.
 *
 * <p>클래스 레벨 {@code @Transactional} 을 쓰지 않는다 — 요청이 각자 커밋되어야 다음 요청이 바뀐 버전을 보고, 동시 저장이 서로의 행 잠금을 기다린다.
 * 정리는 {@link #tearDown()}.
 */
@AutoConfigureMockMvc
class DatasetEditConflictTest extends IntegrationTestBase {

  /** 동시 저장 반복 횟수 — 행 잠금이 없으면 이 안에서 둘 다 통과하는 회차가 나온다. */
  private static final int ROUNDS = 5;

  @Autowired private MockMvc mockMvc;
  @Autowired private DSLContext dsl;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JwtTokenProvider jwt;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private DatasetService datasetService;
  @Autowired private DatasetRepository datasetRepository;

  private SecurityFixture fx;
  private long editor;
  private Long datasetId;

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, passwordEncoder);
    editor = fx.createUser("wd575-ds-");
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, editor, DEFAULT_TEST_TENANT_ID, "ADMIN");
    String suffix = Long.toString(System.nanoTime());
    datasetId =
        inTenantFixture(
                () ->
                    datasetService.createDataset(
                        new CreateDatasetRequest(
                            "wd575 처음 이름 " + suffix,
                            "wd575_" + suffix,
                            "처음 설명",
                            null,
                            "TABLE",
                            "SOURCE",
                            List.of(
                                new DatasetColumnRequest(
                                    "a", "A", "TEXT", null, true, false, null)),
                            null),
                        editor))
            .id();
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    TenantRlsTestSupport.cleanupAll(
        () -> {
          if (datasetId != null) {
            inTenantFixture(() -> datasetService.deleteDataset(datasetId));
          }
        },
        () -> fx.deleteUser(editor));
  }

  /** 상세의 updatedAt 을 그대로 보내면 204 이고, 저장 뒤 다시 읽은 updatedAt 으로 이어 저장해도 통과한다(자기 저장과 충돌하지 않는다). */
  @Test
  void saveWithDetailVersion_succeeds_andRefreshedVersionChainsNextSave() throws Exception {
    JsonNode before = detail();
    String v1 = before.get("updatedAt").asText();

    MvcResult first = save(before.get("name").asText(), "설명 A", v1);
    assertThat(first.getResponse().getStatus()).as(body(first)).isEqualTo(204);
    String v2 = detail().get("updatedAt").asText();
    assertThat(v2).as("저장하면 수정 시각이 바뀐다").isNotEqualTo(v1);

    MvcResult second = save(before.get("name").asText(), "설명 B", v2);
    assertThat(second.getResponse().getStatus()).as(body(second)).isEqualTo(204);
    assertThat(detail().get("description").asText()).isEqualTo("설명 B");
  }

  /**
   * 결함 재현: A 가 이름을 바꾼 뒤, 같은 기준으로 연 옛 화면의 B 가 (옛 이름 + 새 설명)으로 저장하면 409 EDIT_CONFLICT 이고 A 의 이름이 그대로
   * 남는다.
   */
  @Test
  void saveWithStaleVersion_rejected409_andEarlierRenameKept() throws Exception {
    JsonNode opened = detail();
    String base = opened.get("updatedAt").asText();
    String oldName = opened.get("name").asText();
    String renamed = oldName + " (A 가 바꿈)";

    MvcResult a = save(renamed, "처음 설명", base);
    assertThat(a.getResponse().getStatus()).as(body(a)).isEqualTo(204);

    MvcResult b = save(oldName, "B 가 고친 설명", base);
    assertThat(b.getResponse().getStatus()).as(body(b)).isEqualTo(409);
    JsonNode res = objectMapper.readTree(body(b));
    assertThat(res.get("code").asText()).isEqualTo(EditConflictException.CODE);
    assertThat(res.get("message").asText()).startsWith("이 데이터셋은").contains("다른 곳에서 변경되었습니다");

    JsonNode after = detail();
    assertThat(after.get("name").asText()).as("A 의 이름 변경이 되돌려지지 않는다").isEqualTo(renamed);
    assertThat(after.get("description").asText()).isEqualTo("처음 설명");
  }

  /** 기준 버전 없이 보내면(AI 도구 등 기존 호출부, 「내 변경으로 덮어쓰기」) 예전처럼 비교하지 않고 저장된다. */
  @Test
  void saveWithoutVersion_skipsCheck_andOverwrites() throws Exception {
    JsonNode opened = detail();
    String base = opened.get("updatedAt").asText();
    String name = opened.get("name").asText();
    assertThat(save(name, "다른 사용자의 설명", base).getResponse().getStatus()).isEqualTo(204);

    MvcResult overwrite = save(name, "덮어쓴 설명", null);
    assertThat(overwrite.getResponse().getStatus()).as(body(overwrite)).isEqualTo(204);
    assertThat(detail().get("description").asText()).isEqualTo("덮어쓴 설명");
  }

  /** 없는 데이터셋은 기준 버전이 있어도 409 가 아니라 지금과 같은 404 다(존재 확인이 충돌 판정보다 먼저). */
  @Test
  void missingDatasetWithVersion_gets404() throws Exception {
    MvcResult res =
        mockMvc
            .perform(
                put("/api/v1/datasets/999999999")
                    .header("Authorization", token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            Map.of("name", "없음", "expectedUpdatedAt", "2020-01-01T00:00:00"))))
            .andReturn();
    assertThat(res.getResponse().getStatus()).as(body(res)).isEqualTo(404);
  }

  /**
   * 같은 기준으로 동시에 들어온 저장 둘 중 하나만 성공한다 — 비교와 갱신 사이에 끼어들 틈이 없어야 한다(행 잠금). 다른 하나는 편집 충돌로 거부되고, 남은 값은 성공한
   * 쪽의 것이다.
   */
  @Test
  void concurrentSavesWithSameVersion_onlyOneSucceeds() throws Exception {
    for (int i = 0; i < ROUNDS; i++) {
      DatasetResponse current = inTenantFixture(() -> datasetRepository.findById(datasetId).get());
      LocalDateTime base =
          inTenantFixture(() -> datasetRepository.findUpdatedAtById(datasetId).orElseThrow());
      String left = "왼쪽 " + i;
      String right = "오른쪽 " + i;

      List<Object> outcomes =
          runTogether(
              () ->
                  datasetService.updateDataset(
                      datasetId,
                      new UpdateDatasetRequest(current.name(), left, null, base),
                      editor),
              () ->
                  datasetService.updateDataset(
                      datasetId,
                      new UpdateDatasetRequest(current.name(), right, null, base),
                      editor));

      long succeeded = outcomes.stream().filter(o -> o instanceof String).count();
      long conflicted = outcomes.stream().filter(o -> o instanceof EditConflictException).count();
      assertThat(succeeded).as("회차 %d 결과 %s", i, outcomes).isEqualTo(1);
      assertThat(conflicted).as("회차 %d 결과 %s", i, outcomes).isEqualTo(1);
      String winner = outcomes.get(0) instanceof String ? left : right;
      assertThat(inTenantFixture(() -> datasetRepository.findById(datasetId).get().description()))
          .isEqualTo(winner);
    }
  }

  // ── 헬퍼 ──────────────────────────────────────────────────────────────────

  /** 두 작업을 같은 순간에 출발시키고 각 결과를 돌려준다 — 성공이면 "ok", 실패면 그 예외. 각 작업은 자기 트랜잭션(기본 테넌트)에서 돈다. */
  private List<Object> runTogether(Runnable left, Runnable right) throws Exception {
    CyclicBarrier start = new CyclicBarrier(2);
    List<CompletableFuture<Object>> futures = new ArrayList<>();
    for (Runnable task : List.of(left, right)) {
      futures.add(
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  start.await(10, TimeUnit.SECONDS);
                  inTenantFixture(task);
                  return "ok";
                } catch (Exception e) {
                  return e;
                }
              }));
    }
    List<Object> outcomes = new ArrayList<>();
    for (CompletableFuture<Object> f : futures) {
      outcomes.add(f.get(60, TimeUnit.SECONDS));
    }
    return outcomes;
  }

  /** 정보 수정 요청 — expectedUpdatedAt 은 상세에서 받은 문자열을 그대로 싣는다(null 이면 뺀다). */
  private MvcResult save(String name, String description, String expectedUpdatedAt)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", name);
    body.put("description", description);
    if (expectedUpdatedAt != null) {
      body.put("expectedUpdatedAt", expectedUpdatedAt);
    }
    return mockMvc
        .perform(
            put("/api/v1/datasets/" + datasetId)
                .header("Authorization", token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andReturn();
  }

  /** 데이터셋 상세(GET) — 웹이 편집 기준으로 쓰는 updatedAt 이 여기 실린다. */
  private JsonNode detail() throws Exception {
    MvcResult result =
        mockMvc
            .perform(get("/api/v1/datasets/" + datasetId).header("Authorization", token()))
            .andReturn();
    assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);
    return objectMapper.readTree(body(result));
  }

  private static String body(MvcResult result) throws Exception {
    return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
  }

  private String token() {
    return "Bearer " + jwt.generateAccessToken(editor, "u" + editor, DEFAULT_TEST_TENANT_ID);
  }
}
