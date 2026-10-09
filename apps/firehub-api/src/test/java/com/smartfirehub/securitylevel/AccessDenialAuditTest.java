package com.smartfirehub.securitylevel;

import static com.smartfirehub.jooq.Tables.DATASET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import com.smartfirehub.securitylevel.access.SqlAccessMode;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 보충 스펙 §3 — 접근 거부 감사의 대상(VIEW·SQL)과 실제 사유 기록, 값 판정 경로 비감사, 감사 등급 행 조회 기록. 단언은 "기록기 호출"이 아니라
 * MockMvc 응답 뒤 audit_log 행이다(롤백·트랜잭션 배선까지 포함한 증거).
 */
@AutoConfigureMockMvc
class AccessDenialAuditTest extends IntegrationTestBase {

  @Autowired private MockMvc mockMvc;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private JwtTokenProvider jwt;
  @Autowired private DatasetService datasetService;
  @Autowired private DatasetAccessGuard guard;
  @Autowired private ClearanceResolver clearanceResolver;

  private SecurityFixture fx;
  private final List<Long> users = new ArrayList<>();
  private final List<Long> roles = new ArrayList<>();
  private final List<Long> datasets = new ArrayList<>();
  private long creator;
  private long low;
  private String hiddenTable;
  private long hidden;

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    creator = fx.createUser("ada_c");
    users.add(creator);
    hiddenTable = "ada_h_" + System.nanoTime();
    hidden = fx.createDatasetRow(hiddenTable, fx.levelId("기밀"), creator);
    datasets.add(hidden);
    low = userAt("공개", "dataset:read", "data:read", "analytics:read");
  }

  private long userAt(String level, String... perms) {
    long uid = fx.createUser("ada_u");
    users.add(uid);
    fx.removeUserRole(uid);
    long rid = fx.createRole("ada_r_" + System.nanoTime(), fx.levelId(level), perms);
    roles.add(rid);
    fx.assignRole(uid, rid);
    return uid;
  }

  /** 물리 테이블이 있는 데이터셋(행 조회 경로용). 등급은 직접 지정한다. */
  private long realDataset(String level) {
    String t = "ada_t_" + System.nanoTime();
    long id =
        datasetService
            .createDataset(
                new CreateDatasetRequest(
                    t,
                    t,
                    null,
                    null,
                    "TABLE",
                    "SOURCE",
                    List.of(
                        new DatasetColumnRequest("v", "v", "TEXT", null, true, false, null, false)),
                    null),
                creator)
            .id();
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.update(DATASET)
                .set(DATASET.SECURITY_LEVEL_ID, fx.levelId(level))
                .where(DATASET.ID.eq(id))
                .execute());
    datasets.add(id);
    return id;
  }

  private String token(long uid) {
    return "Bearer " + jwt.generateAccessToken(uid, "ada", DEFAULT_TEST_TENANT_ID);
  }

  private List<Record> audits(long uid, String action) {
    return inTenantFixture(
        () ->
            dsl.fetch(
                "SELECT resource_id, result, metadata->>'action' a, metadata->>'reason' r,"
                    + " metadata->>'tableName' t, metadata->>'kind' k FROM audit_log"
                    + " WHERE user_id = ? AND action_type = ? ORDER BY id",
                uid,
                action));
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    for (long u : users) {
      inTenantFixture(() -> dsl.execute("DELETE FROM audit_log WHERE user_id = ?", u));
    }
    datasets.forEach(
        id -> {
          try {
            datasetService.deleteDataset(id);
          } catch (Exception ignored) {
            fx.deleteDatasetRow(id);
          }
        });
    users.forEach(fx::deleteUser);
    roles.forEach(fx::deleteRole);
  }

  @Test
  void hiddenDatasetGet_isAuditedWithRealReason() throws Exception {
    mockMvc
        .perform(get("/api/v1/datasets/" + hidden).header("Authorization", token(low)))
        .andExpect(status().isNotFound());
    List<Record> rows = audits(low, "DATASET_ACCESS_DENIED");
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("resource_id", String.class)).isEqualTo(String.valueOf(hidden));
    assertThat(rows.get(0).get("a", String.class)).isEqualTo("VIEW");
    assertThat(rows.get(0).get("r", String.class)).isEqualTo("CLEARANCE_INSUFFICIENT");
    assertThat(rows.get(0).get("result", String.class)).isEqualTo("FAILURE");
  }

  @Test
  void missingDataset_isNotAudited() throws Exception {
    mockMvc
        .perform(get("/api/v1/datasets/987654321").header("Authorization", token(low)))
        .andExpect(status().isNotFound());
    assertThat(audits(low, "DATASET_ACCESS_DENIED")).isEmpty();
  }

  @Test
  void repeatedDenial_isCoalescedToOne() throws Exception {
    for (int i = 0; i < 3; i++) {
      mockMvc
          .perform(get("/api/v1/datasets/" + hidden).header("Authorization", token(low)))
          .andExpect(status().isNotFound());
    }
    assertThat(audits(low, "DATASET_ACCESS_DENIED")).hasSize(1);
  }

  @Test
  void adhocSqlDenial_recordsTableAndDataset() throws Exception {
    String sql = "SELECT * FROM " + DataSchema.qualify(hiddenTable);
    mockMvc
        .perform(
            post("/api/v1/analytics/queries/execute")
                .header("Authorization", token(low))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sql\":\"" + sql.replace("\"", "\\\"") + "\",\"maxRows\":10}"))
        .andExpect(status().isForbidden());
    List<Record> rows = audits(low, "DATASET_ACCESS_DENIED");
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("a", String.class)).isEqualTo("SQL");
    assertThat(rows.get(0).get("t", String.class)).isEqualTo(hiddenTable);
    assertThat(rows.get(0).get("resource_id", String.class)).isEqualTo(String.valueOf(hidden));
  }

  /** 값 판정(차트 denied 위젯 등이 쓰는 checkSql)은 거부를 감사하지 않는다 — 사용자 요청의 거부가 아니다. */
  @Test
  void valueJudgement_isNotAudited() {
    var r =
        guard.checkSql(
            clearanceResolver.resolve(low),
            "SELECT * FROM " + DataSchema.qualify(hiddenTable),
            SqlAccessMode.INTERACTIVE);
    assertThat(r.allowed()).isFalse();
    assertThat(audits(low, "DATASET_ACCESS_DENIED")).isEmpty();
  }

  @Test
  void auditAccessLevelRowView_isRecorded_publicIsNot() throws Exception {
    long sensitive = realDataset("민감");
    long open = realDataset("공개");
    long viewer = userAt("민감", "dataset:read", "data:read");
    mockMvc
        .perform(
            get("/api/v1/datasets/" + sensitive + "/data").header("Authorization", token(viewer)))
        .andExpect(status().isOk());
    mockMvc
        .perform(get("/api/v1/datasets/" + open + "/data").header("Authorization", token(viewer)))
        .andExpect(status().isOk());
    List<Record> rows = audits(viewer, "DATASET_ACCESS");
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("resource_id", String.class)).isEqualTo(String.valueOf(sensitive));
    assertThat(rows.get(0).get("k", String.class)).isEqualTo("ROW_VIEW");
  }
}
