package com.smartfirehub.securitylevel;

import static com.smartfirehub.jooq.Tables.DATASET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.job.service.AsyncJobService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * 스펙 §2.2·§4.4 — 서버 내보내기·비동기 파일·presigned attachment 는 export_policy 를 강제하고, 거부는 감사된다. 데이터셋 상세·목록은
 * 조회자 기준 exportAllowed 를 싣는다. 감사 단언은 MockMvc 응답 뒤 audit_log 행이다(롤백·트랜잭션 배선까지 포함한 증거).
 */
@AutoConfigureMockMvc
class DataExportPolicyTest extends IntegrationTestBase {

  private static final String CSV = "{\"format\":\"CSV\"}";

  @Autowired private MockMvc mockMvc;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private JwtTokenProvider jwt;
  @Autowired private DatasetService datasetService;
  @Autowired private AsyncJobService asyncJobService;

  private SecurityFixture fx;
  private final List<Long> users = new ArrayList<>();
  private final List<Long> roles = new ArrayList<>();
  private final List<Long> datasets = new ArrayList<>();
  private final Map<Long, String> tableNames = new HashMap<>();
  private final List<Path> files = new ArrayList<>();
  private long creator;

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    creator = fx.createUser("dep_c");
    users.add(creator);
  }

  /** 지정 등급 자격 + 권한만 가진 사용자(기본 역할 제거). */
  private long userAt(String level, String... perms) {
    long uid = fx.createUser("dep_u");
    users.add(uid);
    fx.removeUserRole(uid);
    long rid = fx.createRole("dep_r_" + System.nanoTime(), fx.levelId(level), perms);
    roles.add(rid);
    fx.assignRole(uid, rid);
    return uid;
  }

  /** 물리 테이블이 있는 TABLE 데이터셋(동기 내보내기 경로용). 등급은 직접 지정한다. */
  private long dataset(String level) {
    String t = "dep_t_" + System.nanoTime();
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
    setLevel(id, level);
    datasets.add(id);
    tableNames.put(id, t);
    return id;
  }

  private void setLevel(long id, String level) {
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.update(DATASET)
                .set(DATASET.SECURITY_LEVEL_ID, fx.levelId(level))
                .where(DATASET.ID.eq(id))
                .execute());
  }

  private String token(long uid) {
    return "Bearer " + jwt.generateAccessToken(uid, "dep", DEFAULT_TEST_TENANT_ID);
  }

  /** 사용자의 거부 감사 행(action·reason·datasetId). */
  private List<Record> denials(long uid) {
    awaitSecurityAudit();
    return inTenantFixture(
        () ->
            dsl.fetch(
                "SELECT resource_id, metadata->>'action' a, metadata->>'reason' r FROM audit_log"
                    + " WHERE user_id = ? AND action_type = 'DATASET_ACCESS_DENIED' ORDER BY id",
                uid));
  }

  @AfterEach
  void tearDown() throws Exception {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    for (long u : users) {
      inTenantFixture(() -> dsl.execute("DELETE FROM audit_log WHERE user_id = ?", u));
      inTenantFixture(() -> dsl.execute("DELETE FROM async_job WHERE user_id = ?", u));
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
    for (Path f : files) {
      Files.deleteIfExists(f);
    }
  }

  @Test
  void publicDataset_exports() throws Exception {
    long ds = dataset("공개");
    long u = userAt("공개", "dataset:read", "data:export");
    mockMvc
        .perform(
            post("/api/v1/datasets/" + ds + "/export")
                .header("Authorization", token(u))
                .contentType(MediaType.APPLICATION_JSON)
                .content(CSV))
        .andExpect(status().isOk());
  }

  @Test
  void denyLevel_isPolicyBlocked_evenForViewer() throws Exception {
    long ds = dataset("기밀");
    long u = userAt("기밀", "dataset:read", "data:export", "data:export_restricted");
    fx.grantUser(ds, u);
    mockMvc
        .perform(
            post("/api/v1/datasets/" + ds + "/export")
                .header("Authorization", token(u))
                .contentType(MediaType.APPLICATION_JSON)
                .content(CSV))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("POLICY_BLOCKED"))
        .andExpect(jsonPath("$.errors.action").value("EXPORT"))
        .andExpect(jsonPath("$.errors.policyKey").value("export_policy"))
        .andExpect(jsonPath("$.errors.levelName").value("기밀"))
        .andExpect(jsonPath("$.message").value("'기밀' 등급 데이터는 내보낼 수 없습니다."));
  }

  /** 볼 수 없는 데이터셋은 정책 거부가 아니라 "없음"과 같은 404 — 등급 이름을 흘리지 않는다(존재 은닉). */
  @Test
  void hiddenDataset_isNotFound_notPolicyBlocked() throws Exception {
    long ds = dataset("기밀");
    long u = userAt("공개", "dataset:read", "data:export");
    mockMvc
        .perform(
            post("/api/v1/datasets/" + ds + "/export")
                .header("Authorization", token(u))
                .contentType(MediaType.APPLICATION_JSON)
                .content(CSV))
        .andExpect(status().isNotFound());
  }

  @Test
  void permissionLevel_requiresRestrictedExportPermission() throws Exception {
    long ds = dataset("민감");
    long without = userAt("민감", "dataset:read", "data:export");
    long with = userAt("민감", "dataset:read", "data:export", "data:export_restricted");
    mockMvc
        .perform(
            post("/api/v1/datasets/" + ds + "/export")
                .header("Authorization", token(without))
                .contentType(MediaType.APPLICATION_JSON)
                .content(CSV))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("POLICY_BLOCKED"))
        .andExpect(jsonPath("$.message").value("'민감' 등급 데이터를 내보내려면 제한 데이터 내보내기 권한이 필요합니다."));
    mockMvc
        .perform(
            post("/api/v1/datasets/" + ds + "/export")
                .header("Authorization", token(with))
                .contentType(MediaType.APPLICATION_JSON)
                .content(CSV))
        .andExpect(status().isOk());
  }

  /**
   * 데이터셋 내보내기 거부는 실제 사유·데이터셋 id 와 함께 EXPORT 동작으로 감사된다(403 응답 뒤 행 1건).
   *
   * <p>이 경로의 판정은 컨트롤러(트랜잭션 밖)라 호출자 롤백 상황을 만들지 못한다. 감사가 호출자 롤백에도 살아남는다는 증명은 {@code
   * attachmentPresign_requiresExport_inlineDoesNot}(@Transactional 경로)과 {@code
   * SecurityAuditRecorderTest.denial_survivesCallerRollback} 이 맡는다.
   */
  @Test
  void deniedExport_isAuditedWithActualReason() throws Exception {
    long ds = dataset("민감");
    long u = userAt("민감", "dataset:read", "data:export");
    mockMvc
        .perform(
            post("/api/v1/datasets/" + ds + "/export")
                .header("Authorization", token(u))
                .contentType(MediaType.APPLICATION_JSON)
                .content(CSV))
        .andExpect(status().isForbidden());
    List<Record> rows = denials(u);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("a", String.class)).isEqualTo("EXPORT");
    assertThat(rows.get(0).get("r", String.class)).isEqualTo("EXPORT_PERMISSION_REQUIRED");
    assertThat(rows.get(0).get("resource_id", String.class)).isEqualTo(String.valueOf(ds));
  }

  /** Review Focus 4 — 비동기 파일은 다운로드 시점 등급으로 다시 판정한다. */
  @Test
  void asyncFile_isRejudgedAtDownload() throws Exception {
    long ds = dataset("공개");
    long u = userAt("기밀", "dataset:read", "data:export", "data:export_restricted");
    Path file = Files.createTempFile("dep", ".csv");
    files.add(file);
    Files.writeString(file, "v\nx\n");
    String jobId =
        inTenantFixture(
            () ->
                asyncJobService.createJob(
                    "DATA_EXPORT",
                    "dataset",
                    String.valueOf(ds),
                    u,
                    Map.of("filename", "x.csv", "contentType", "text/csv")));
    inTenantFixture(
        () ->
            asyncJobService.completeJob(
                jobId,
                Map.of(
                    "filePath", file.toString(), "filename", "x.csv", "contentType", "text/csv")));
    mockMvc
        .perform(get("/api/v1/exports/" + jobId + "/file").header("Authorization", token(u)))
        .andExpect(status().isOk());

    setLevel(ds, "기밀");
    fx.grantUser(ds, u);
    mockMvc
        .perform(get("/api/v1/exports/" + jobId + "/file").header("Authorization", token(u)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("POLICY_BLOCKED"));
  }

  /**
   * attachment presign 은 내보내기 판정을 먼저 한다 — FILE 데이터셋이 아니어도 정책 거부가 먼저 난다(허용이면 400 "FILE 데이터셋이 아닙니다").
   * inline 은 판정하지 않는다. 이 엔드포인트는 @Transactional(readOnly) 라 거부 예외가 그 트랜잭션을 롤백한다 — 감사 행이 남는 것이
   * REQUIRES_NEW 의 증거다(Review Focus 1).
   */
  @Test
  void attachmentPresign_requiresExport_inlineDoesNot() throws Exception {
    long secret = dataset("기밀");
    long open = dataset("공개");
    long u = userAt("기밀", "dataset:read", "data:export");
    fx.grantUser(secret, u);
    mockMvc
        .perform(
            get("/api/v1/datasets/" + secret + "/objects/url")
                .param("key", "x")
                .param("disposition", "attachment")
                .header("Authorization", token(u)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("POLICY_BLOCKED"));
    List<Record> rows = denials(u);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("a", String.class)).isEqualTo("EXPORT");
    assertThat(rows.get(0).get("r", String.class)).isEqualTo("EXPORT_DENIED");

    mockMvc
        .perform(
            get("/api/v1/datasets/" + secret + "/objects/url")
                .param("key", "x")
                .param("disposition", "inline")
                .header("Authorization", token(u)))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            get("/api/v1/datasets/" + open + "/objects/url")
                .param("key", "x")
                .param("disposition", "attachment")
                .header("Authorization", token(u)))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            get("/api/v1/datasets/" + open + "/objects/url")
                .param("key", "x")
                .param("disposition", "download")
                .header("Authorization", token(u)))
        .andExpect(status().isBadRequest());
  }

  /** attachment 는 data:export 권한도 요구한다 — 엔드포인트 자체는 dataset:read 라 권한 검사를 가드가 대신한다. */
  @Test
  void attachmentPresign_withoutExportPermission_isForbidden() throws Exception {
    long open = dataset("공개");
    long u = userAt("공개", "dataset:read");
    mockMvc
        .perform(
            get("/api/v1/datasets/" + open + "/objects/url")
                .param("key", "x")
                .param("disposition", "attachment")
                .header("Authorization", token(u)))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            get("/api/v1/datasets/" + open + "/objects/url")
                .param("key", "x")
                .header("Authorization", token(u)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void detailAndList_carryViewerExportAllowed() throws Exception {
    long ds = dataset("민감");
    String table = tableNames.get(ds);
    long without = userAt("민감", "dataset:read", "data:export");
    long with = userAt("민감", "dataset:read", "data:export", "data:export_restricted");
    long noExport = userAt("민감", "dataset:read", "data:export_restricted");
    mockMvc
        .perform(get("/api/v1/datasets/" + ds).header("Authorization", token(without)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.exportAllowed").value(false));
    mockMvc
        .perform(get("/api/v1/datasets/" + ds).header("Authorization", token(with)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.exportAllowed").value(true));
    // data:export 권한이 없으면 정책이 허용해도 false — 내보내기 엔드포인트를 쓸 수 없다.
    mockMvc
        .perform(get("/api/v1/datasets/" + ds).header("Authorization", token(noExport)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.exportAllowed").value(false));
    mockMvc
        .perform(
            get("/api/v1/datasets").param("search", table).header("Authorization", token(with)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[?(@.id == " + ds + ")].exportAllowed").value(true));
    mockMvc
        .perform(
            get("/api/v1/datasets").param("search", table).header("Authorization", token(without)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[?(@.id == " + ds + ")].exportAllowed").value(false));
  }
}
