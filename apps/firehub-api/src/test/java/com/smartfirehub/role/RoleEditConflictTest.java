package com.smartfirehub.role;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.exception.EditConflictException;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.role.repository.RoleRepository;
import com.smartfirehub.role.service.RoleService;
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
 * 역할 이름·설명 수정의 편집 충돌(낙관적 잠금) — WD-575.
 *
 * <p>결함: {@code PUT /api/v1/roles/{id}} 가 이름·설명을 통째로 바꾸는데 기준 버전 비교가 없어, A 가 이름을 바꾼 뒤 옛 화면의 B 가 설명만
 * 고쳐 저장하면 옛 이름이 다시 써져 A 의 변경이 조용히 사라졌다. 이제 웹이 상세의 {@code updatedAt} 을 {@code expectedUpdatedAt} 으로
 * 보내고, 다르면 409 EDIT_CONFLICT 로 거부한다. 권한 목록 저장은 {@code expectedPermissionIds} 로 따로 보호되며(WD-440) 수정
 * 시각을 바꾸지 않는다.
 *
 * <p>HTTP 왕복(MockMvc)으로 하는 이유는 상세의 {@code updatedAt} 문자열을 그대로 되돌려 보냈을 때 같다고 판정되는지(직렬화 정밀도)가 핵심이기
 * 때문이다. 동시 저장은 서비스를 두 스레드에서 직접 부른다. 클래스 레벨 {@code @Transactional} 을 쓰지 않는다 — 요청이 각자 커밋되어야 다음 요청이 바뀐
 * 버전을 본다.
 */
@AutoConfigureMockMvc
class RoleEditConflictTest extends IntegrationTestBase {

  /** 동시 저장 반복 횟수 — 행 잠금이 없으면 이 안에서 둘 다 통과하는 회차가 나온다. */
  private static final int ROUNDS = 5;

  @Autowired private MockMvc mockMvc;
  @Autowired private DSLContext dsl;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JwtTokenProvider jwt;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private RoleService roleService;
  @Autowired private RoleRepository roleRepository;

  private SecurityFixture fx;
  private long admin;
  private Long roleId;
  private String roleName;

  @BeforeEach
  void setUp() throws Exception {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, passwordEncoder);
    admin = fx.createUser("wd575-role-");
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, admin, DEFAULT_TEST_TENANT_ID, "ADMIN");
    roleName = "WD575_" + System.nanoTime();
    MvcResult created =
        mockMvc
            .perform(
                post("/api/v1/roles")
                    .header("Authorization", token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            Map.of("name", roleName, "description", "처음 설명"))))
            .andReturn();
    assertThat(created.getResponse().getStatus()).as(body(created)).isEqualTo(201);
    roleId = objectMapper.readTree(body(created)).get("id").asLong();
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    TenantRlsTestSupport.cleanupAll(
        () -> {
          if (roleId != null) {
            inTenantFixture(
                DEFAULT_TEST_TENANT_ID, () -> dsl.execute("delete from role where id = ?", roleId));
          }
        },
        () -> fx.deleteUser(admin));
  }

  /** 상세의 updatedAt 을 그대로 보내면 204 이고, 저장 뒤 다시 읽은 updatedAt 으로 이어 저장해도 통과한다(자기 저장과 충돌하지 않는다). */
  @Test
  void saveWithDetailVersion_succeeds_andRefreshedVersionChainsNextSave() throws Exception {
    String v1 = detail().get("updatedAt").asText();
    assertThat(v1).as("상세에 편집 기준 버전이 실린다").isNotBlank().isNotEqualTo("null");

    MvcResult first = save(roleName, "설명 A", v1);
    assertThat(first.getResponse().getStatus()).as(body(first)).isEqualTo(204);
    String v2 = detail().get("updatedAt").asText();
    assertThat(v2).as("저장하면 수정 시각이 바뀐다").isNotEqualTo(v1);

    MvcResult second = save(roleName, "설명 B", v2);
    assertThat(second.getResponse().getStatus()).as(body(second)).isEqualTo(204);
    assertThat(detail().get("description").asText()).isEqualTo("설명 B");
  }

  /** 결함 재현: A 가 이름을 바꾼 뒤 옛 화면의 B 가 (옛 이름 + 새 설명)으로 저장하면 409 EDIT_CONFLICT 이고 A 의 이름이 그대로 남는다. */
  @Test
  void saveWithStaleVersion_rejected409_andEarlierRenameKept() throws Exception {
    String base = detail().get("updatedAt").asText();
    String renamed = roleName + "_A";

    MvcResult a = save(renamed, "처음 설명", base);
    assertThat(a.getResponse().getStatus()).as(body(a)).isEqualTo(204);

    MvcResult b = save(roleName, "B 가 고친 설명", base);
    assertThat(b.getResponse().getStatus()).as(body(b)).isEqualTo(409);
    JsonNode res = objectMapper.readTree(body(b));
    assertThat(res.get("code").asText()).isEqualTo(EditConflictException.CODE);
    assertThat(res.get("message").asText()).isEqualTo(RoleService.EDIT_CONFLICT_MESSAGE);

    JsonNode after = detail();
    assertThat(after.get("name").asText()).as("A 의 이름 변경이 되돌려지지 않는다").isEqualTo(renamed);
    assertThat(after.get("description").asText()).isEqualTo("처음 설명");
  }

  /** 기준 버전 없이 보내면(기존 호출부, 「내 변경으로 덮어쓰기」) 예전처럼 비교하지 않고 저장된다. */
  @Test
  void saveWithoutVersion_skipsCheck_andOverwrites() throws Exception {
    String base = detail().get("updatedAt").asText();
    assertThat(save(roleName, "다른 관리자의 설명", base).getResponse().getStatus()).isEqualTo(204);

    MvcResult overwrite = save(roleName, "덮어쓴 설명", null);
    assertThat(overwrite.getResponse().getStatus()).as(body(overwrite)).isEqualTo(204);
    assertThat(detail().get("description").asText()).isEqualTo("덮어쓴 설명");
  }

  /** 권한 목록 저장은 수정 시각을 바꾸지 않는다 — 권한을 저장한 뒤에도 처음 읽은 기준으로 이름·설명을 저장할 수 있어야 한다. */
  @Test
  void permissionSave_doesNotBumpVersion() throws Exception {
    String base = detail().get("updatedAt").asText();
    MvcResult perms =
        mockMvc
            .perform(
                put("/api/v1/roles/" + roleId + "/permissions")
                    .header("Authorization", token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of("permissionIds", List.of()))))
            .andReturn();
    assertThat(perms.getResponse().getStatus()).as(body(perms)).isLessThan(300);
    assertThat(detail().get("updatedAt").asText()).isEqualTo(base);

    MvcResult res = save(roleName, "권한 저장 뒤 설명", base);
    assertThat(res.getResponse().getStatus()).as(body(res)).isEqualTo(204);
  }

  /** 없는 역할은 기준 버전이 있어도 409 가 아니라 지금과 같은 404 다. */
  @Test
  void missingRoleWithVersion_gets404() throws Exception {
    MvcResult res =
        mockMvc
            .perform(
                put("/api/v1/roles/999999999")
                    .header("Authorization", token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            Map.of("name", "NONE", "expectedUpdatedAt", "2020-01-01T00:00:00"))))
            .andReturn();
    assertThat(res.getResponse().getStatus()).as(body(res)).isEqualTo(404);
  }

  /** 같은 기준으로 동시에 들어온 저장 둘 중 하나만 성공한다(행 잠금) — 다른 하나는 편집 충돌로 거부되고 남은 값은 성공한 쪽의 것이다. */
  @Test
  void concurrentSavesWithSameVersion_onlyOneSucceeds() throws Exception {
    for (int i = 0; i < ROUNDS; i++) {
      LocalDateTime base = inTenantFixture(() -> roleRepository.findUpdatedAtById(roleId));
      String left = "왼쪽 " + i;
      String right = "오른쪽 " + i;

      List<Object> outcomes =
          runTogether(
              () -> roleService.updateRole(roleId, roleName, left, base, admin),
              () -> roleService.updateRole(roleId, roleName, right, base, admin));

      long succeeded = outcomes.stream().filter(o -> o instanceof String).count();
      long conflicted = outcomes.stream().filter(o -> o instanceof EditConflictException).count();
      assertThat(succeeded).as("회차 %d 결과 %s", i, outcomes).isEqualTo(1);
      assertThat(conflicted).as("회차 %d 결과 %s", i, outcomes).isEqualTo(1);
      String winner = outcomes.get(0) instanceof String ? left : right;
      assertThat(inTenantFixture(() -> roleRepository.findById(roleId).get().description()))
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

  /** 이름·설명 수정 요청 — expectedUpdatedAt 은 상세에서 받은 문자열을 그대로 싣는다(null 이면 뺀다). */
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
            put("/api/v1/roles/" + roleId)
                .header("Authorization", token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andReturn();
  }

  /** 역할 상세(GET) — 웹이 편집 기준으로 쓰는 updatedAt 이 여기 실린다. */
  private JsonNode detail() throws Exception {
    MvcResult result =
        mockMvc
            .perform(get("/api/v1/roles/" + roleId).header("Authorization", token()))
            .andReturn();
    assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);
    return objectMapper.readTree(body(result));
  }

  private static String body(MvcResult result) throws Exception {
    return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
  }

  private String token() {
    return "Bearer " + jwt.generateAccessToken(admin, "u" + admin, DEFAULT_TEST_TENANT_ID);
  }
}
