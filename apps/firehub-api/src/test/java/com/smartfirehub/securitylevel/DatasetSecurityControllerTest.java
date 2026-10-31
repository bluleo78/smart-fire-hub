package com.smartfirehub.securitylevel;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/** dataset:classify 없이는 등급 변경 불가, 있으면 가능 — @RequirePermission 배선 고정. */
@AutoConfigureMockMvc
class DatasetSecurityControllerTest extends IntegrationTestBase {

  @Autowired private MockMvc mockMvc;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private JwtTokenProvider jwt;

  private SecurityFixture fx;
  private long uid;
  private long roleId;
  private long ds;
  private long creator;

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    creator = fx.createUser("dsc_creator");
    uid = fx.createUser("dsc");
    ds = fx.createDatasetRow("dsc_" + System.nanoTime(), fx.levelId("내부"), creator);
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    fx.deleteDatasetRow(ds);
    fx.deleteUser(uid);
    fx.deleteUser(creator);
    if (roleId != 0) fx.deleteRole(roleId);
  }

  private String body() {
    return "{\"securityLevelId\":" + fx.levelId("민감") + "}";
  }

  private String token() {
    return "Bearer " + jwt.generateAccessToken(uid, "dsc", DEFAULT_TEST_TENANT_ID);
  }

  @Test
  void classify_withoutPermission_isForbidden() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/datasets/" + ds + "/security-level")
                .header("Authorization", token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body()))
        .andExpect(status().isForbidden());
  }

  @Test
  void classify_withPermission_succeeds() throws Exception {
    roleId =
        fx.createRole(
            "dsc_r_" + System.nanoTime(), fx.levelId("민감"), "dataset:read", "dataset:classify");
    fx.assignRole(uid, roleId);
    mockMvc
        .perform(
            put("/api/v1/datasets/" + ds + "/security-level")
                .header("Authorization", token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body()))
        .andExpect(status().isNoContent());
  }

  /** 후보 조회는 dataset:grant 가 필요하다(dataset:read·classify 만으로는 불가). */
  @Test
  void candidates_requiresGrantPermission() throws Exception {
    roleId =
        fx.createRole(
            "dsc_r_" + System.nanoTime(), fx.levelId("민감"), "dataset:read", "dataset:classify");
    fx.assignRole(uid, roleId);
    mockMvc
        .perform(
            get("/api/v1/datasets/" + ds + "/access-grants/candidates")
                .header("Authorization", token()))
        .andExpect(status().isForbidden());
  }

  /**
   * WD-292 — 후보·허용 목록 응답에 대상 열람 등급과 충족 여부가 JSON 필드로 실린다. 「기밀」 데이터셋에서 호출자 역할(기밀)은 충족, 기본 USER
   * 역할(내부)만 가진 사용자 항목은 부족.
   */
  @Test
  void candidatesAndGrants_carryClearanceFields() throws Exception {
    long secret = fx.levelId("기밀");
    long secretDs = fx.createDatasetRow("dsc_s_" + System.nanoTime(), secret, creator);
    try {
      roleId = fx.createRole("dsc_r_" + System.nanoTime(), secret, "dataset:read", "dataset:grant");
      fx.assignRole(uid, roleId);
      fx.grantRole(secretDs, roleId);
      fx.grantUser(secretDs, creator); // creator 는 USER(내부) 역할만 가진다
      mockMvc
          .perform(
              get("/api/v1/datasets/" + secretDs + "/access-grants/candidates")
                  .header("Authorization", token()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.roles[?(@.id == " + roleId + ")].clearance.levelName").value("기밀"))
          .andExpect(jsonPath("$.roles[?(@.id == " + roleId + ")].sufficientClearance").value(true))
          .andExpect(
              jsonPath("$.users[?(@.id == " + creator + ")].clearance.levelName").value("내부"))
          .andExpect(
              jsonPath("$.users[?(@.id == " + creator + ")].sufficientClearance").value(false));
      mockMvc
          .perform(
              get("/api/v1/datasets/" + secretDs + "/access-grants")
                  .header("Authorization", token()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[?(@.type == 'ROLE')].subjectClearance.levelName").value("기밀"))
          .andExpect(jsonPath("$[?(@.type == 'ROLE')].sufficientClearance").value(true))
          .andExpect(jsonPath("$[?(@.type == 'USER')].subjectClearance.levelName").value("내부"))
          .andExpect(jsonPath("$[?(@.type == 'USER')].sufficientClearance").value(false));
    } finally {
      TenantContext.set(DEFAULT_TEST_TENANT_ID);
      fx.deleteDatasetRow(secretDs);
    }
  }
}
