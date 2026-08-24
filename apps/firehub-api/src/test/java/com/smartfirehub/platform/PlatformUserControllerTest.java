package com.smartfirehub.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 운영자 평면 사용자 검색 TC.
 *
 * <p>가장 중요한 단언은 <b>교차 테넌트 가시성</b>이다. {@code "user"} 가 만약 RLS 였다면 플랫폼
 * 토큰에는 GUC 가 없어 조용히 0행이 나오고, 화면은 그것을 "검색 결과 없음"으로 그린다 — 예외도
 * 로그도 없는 무동작이다. 그래서 서로 다른 테넌트에 소속된 두 사용자를 심고 <b>둘 다</b> 잡히는지
 * 본다. 한 명만 심으면 깨진 쿼리에서도 우연히 통과할 수 있다.
 */
@AutoConfigureMockMvc
class PlatformUserControllerTest extends IntegrationTestBase {

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtTokenProvider jwtTokenProvider;
  @Autowired private DSLContext dsl;
  @Autowired private ObjectMapper objectMapper;

  /** 공유 test DB 라 검색어가 다른 테스트의 행을 긁지 않도록 나노초로 유일화한다. */
  private String marker;

  private String operatorToken;
  private final List<Long> createdUserIds = new ArrayList<>();
  private final List<Long> createdTenantIds = new ArrayList<>();

  @BeforeEach
  void setUp() {
    marker = "p7c2a" + System.nanoTime();
    operatorToken = jwtTokenProvider.generatePlatformAccessToken(createUser(marker + "-ops", true), "ops");
  }

  @AfterEach
  void cleanUp() {
    // 자기가 만든 행만 지운다. 공유 test DB 에서 남의 행을 지우면 무관한 테스트가 깨진다.
    // 순서가 중요하다: membership → user → tenant. FK 에 cascade 가 없다.
    // deleteMembership 은 **사용자 단위**로 지운다(멤버십 id 가 아니다).
    createdUserIds.forEach(id -> TenantRlsTestSupport.deleteMembership(dsl, id));
    createdUserIds.forEach(id -> TenantRlsTestSupport.deleteUser(dsl, id));
    TenantRlsTestSupport.deleteTenants(dsl, createdTenantIds.toArray(Long[]::new));
  }

  private long createUser(String username, boolean platformRole) {
    long userId = TenantRlsTestSupport.insertUserWithPassword(dsl, username, "{noop}x");
    createdUserIds.add(userId);
    if (platformRole) {
      TenantRlsTestSupport.grantPlatformSuperAdmin(dsl, userId);
    }
    return userId;
  }

  @Test
  void searchesAcrossTenants() throws Exception {
    // 서로 다른 테넌트에 소속된 두 사용자.
    long tenantA = TenantRlsTestSupport.createActiveTenant(dsl, marker + "-a");
    long tenantB = TenantRlsTestSupport.createActiveTenant(dsl, marker + "-b");
    createdTenantIds.add(tenantA);
    createdTenantIds.add(tenantB);

    long userA = createUser(marker + "-alpha", false);
    long userB = createUser(marker + "-bravo", false);
    // 반환값이 없다(void). 정리는 @AfterEach 가 사용자 id 로 한다.
    TenantRlsTestSupport.insertActiveMembership(dsl, userA, tenantA);
    TenantRlsTestSupport.insertActiveMembership(dsl, userB, tenantB);

    String body =
        mockMvc
            .perform(get("/api/platform/users").param("q", marker)
                .header("Authorization", "Bearer " + operatorToken))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    List<Long> ids =
        objectMapper.readTree(body).findValuesAsText("id").stream().map(Long::valueOf).toList();
    assertThat(ids).contains(userA, userB);
  }

  @Test
  void returnsMinimalFields() throws Exception {
    long target = createUser(marker + "-charlie", false);

    mockMvc
        .perform(get("/api/platform/users").param("q", marker + "-charlie")
            .header("Authorization", "Bearer " + operatorToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(target))
        .andExpect(jsonPath("$[0].name").value(marker + "-charlie"))
        .andExpect(jsonPath("$[0].email").value(marker + "-charlie@example.com"))
        // 비밀번호·활성여부·가입일은 절대 나가지 않는다 — 열거 표면을 넓히지 않는다.
        .andExpect(jsonPath("$[0].password").doesNotExist())
        .andExpect(jsonPath("$[0].username").doesNotExist())
        .andExpect(jsonPath("$[0].isActive").doesNotExist());
  }

  @Test
  void rejectsShortQuery() throws Exception {
    mockMvc
        .perform(get("/api/platform/users").param("q", "a")
            .header("Authorization", "Bearer " + operatorToken))
        .andExpect(status().isBadRequest());
  }

  @Test
  void rejectsMissingQuery() throws Exception {
    mockMvc
        .perform(get("/api/platform/users").header("Authorization", "Bearer " + operatorToken))
        .andExpect(status().isBadRequest());
  }

  @Test
  void rejectsBlankQuery() throws Exception {
    mockMvc
        .perform(get("/api/platform/users").param("q", "   ")
            .header("Authorization", "Bearer " + operatorToken))
        .andExpect(status().isBadRequest());
  }

  @Test
  void truncatesToLimit() throws Exception {
    // 상한(20) 보다 많이 심는다. 절단이 없으면 25 가 그대로 나온다.
    for (int i = 0; i < 25; i++) {
      createUser(marker + "-bulk-" + i, false);
    }

    mockMvc
        .perform(get("/api/platform/users").param("q", marker + "-bulk")
            .header("Authorization", "Bearer " + operatorToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(20));
  }

  @Test
  void escapesLikeWildcards() throws Exception {
    // '%' 를 이스케이프하지 않으면 이 검색어가 전 사용자를 긁는다.
    long delta = createUser(marker + "-delta", false);

    mockMvc
        .perform(get("/api/platform/users").param("q", "%")
            .header("Authorization", "Bearer " + operatorToken))
        // 2자 미만이라 하한에서 먼저 걸린다.
        .andExpect(status().isBadRequest());

    String body =
        mockMvc
            .perform(get("/api/platform/users").param("q", "%%")
                .header("Authorization", "Bearer " + operatorToken))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    // 결과가 0건이라고 단언하지 않는다 — 공유 test DB 에 이름/이메일에 리터럴 '%' 가 든 행이
    // 있으면 간헐 실패한다. 검증하고 싶은 성질은 "이스케이프가 동작한다" 이므로, 방금 만든
    // 사용자가 '%%' 검색에 걸리지 **않는지**만 본다(이스케이프가 없으면 반드시 걸린다).
    List<Long> ids =
        objectMapper.readTree(body).findValuesAsText("id").stream().map(Long::valueOf).toList();
    assertThat(ids).doesNotContain(delta);
  }

  @Test
  void withoutPlatformPermissions_isForbidden() throws Exception {
    String weak =
        jwtTokenProvider.generatePlatformAccessToken(createUser(marker + "-nobody", false), "nobody");

    mockMvc
        .perform(get("/api/platform/users").param("q", marker)
            .header("Authorization", "Bearer " + weak))
        .andExpect(status().isForbidden());
  }

  @Test
  void tenantTokenCannotReachPlatformPlane() throws Exception {
    String tenantToken = jwtTokenProvider.generateAccessToken(1L, "user", 1L);

    mockMvc
        .perform(get("/api/platform/users").param("q", marker)
            .header("Authorization", "Bearer " + tenantToken))
        .andExpect(status().isForbidden());
  }
}
