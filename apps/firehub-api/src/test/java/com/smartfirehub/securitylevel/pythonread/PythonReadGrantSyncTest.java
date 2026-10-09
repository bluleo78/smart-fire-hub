package com.smartfirehub.securitylevel.pythonread;

import static com.smartfirehub.jooq.Tables.DATASET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantPipelineRole;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.PostgresTestContainer;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * PythonReadGrantSync 실측(스펙 §4.3, WD-29) — "GRANT 문을 실행했다"가 아니라 슬롯 롤로 <b>실제 로그인해 SELECT</b> 한
 * 결과(성공/42501)를 단언한다. 테넌트 1 V133 시드: 공개(위치 1)·내부(2)·민감(3)·기밀(4, allowlist_required).
 */
class PythonReadGrantSyncTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private DatasetService datasetService;
  @Autowired private DataTableService dataTableService;
  @Autowired private PythonReadGrantSync sync;
  @Autowired private ClearanceResolver clearanceResolver;

  @Value("${app.pipeline.role-password-secret}")
  private String secret;

  @Autowired
  @Qualifier("schemaOwnerDataSource")
  private DataSource schemaOwnerDataSource;

  private SecurityFixture fx;
  private final List<Long> datasets = new ArrayList<>();
  private final List<Long> users = new ArrayList<>();
  private final List<Long> roles = new ArrayList<>();
  private long owner;
  private String m;

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    owner = fx.createUser("prs_owner");
    users.add(owner);
    m = "prs" + System.nanoTime();
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
  }

  /** PipelineSqlAccessTest.table 과 같은 방식: 물리 테이블 + 행 1개 + 등급 직접 지정. */
  private String table(String suffix, String level) {
    String t = m + "_" + suffix;
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
                owner)
            .id();
    datasets.add(id);
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> {
          dsl.update(DATASET)
              .set(DATASET.SECURITY_LEVEL_ID, fx.levelId(level))
              .where(DATASET.ID.eq(id))
              .execute();
          dsl.execute("INSERT INTO " + DataSchema.qualify(t) + " (v) VALUES ('row')");
        });
    return t;
  }

  /** 슬롯 롤로 실제 로그인해 SELECT 한다. 권한 오류면 SQLState 를 돌려준다(성공이면 null). */
  private String selectAs(int slot, String table) {
    String role = TenantPipelineRole.pythonReadRoleName(DEFAULT_TEST_TENANT_ID, slot);
    String pw = TenantPipelineRole.pythonReadPassword(DEFAULT_TEST_TENANT_ID, slot, secret);
    try (Connection c =
            DriverManager.getConnection(PostgresTestContainer.INSTANCE.getJdbcUrl(), role, pw);
        Statement s = c.createStatement()) {
      s.executeQuery(
          "SELECT v FROM " + DataSchema.forTenant(DEFAULT_TEST_TENANT_ID) + ".\"" + table + "\"");
      return null;
    } catch (SQLException e) {
      return e.getSQLState();
    }
  }

  private DSLContext ownerDsl() {
    return DSL.using(schemaOwnerDataSource, SQLDialect.POSTGRES);
  }

  private String ownerQualified(String t) {
    return DataSchema.forTenant(DEFAULT_TEST_TENANT_ID) + ".\"" + t + "\"";
  }

  @Test
  void slot2_readsPublicAndInternal_deniedSensitive() {
    String pub = table("pub", "공개");
    String internal = table("int", "내부");
    String sens = table("sen", "민감");
    sync.syncTenant();
    assertThat(selectAs(2, pub)).isNull();
    assertThat(selectAs(2, internal)).isNull();
    assertThat(selectAs(2, sens)).isEqualTo("42501");
    assertThat(selectAs(1, internal)).isEqualTo("42501");
  }

  /** 최상위가 허용 목록 등급이어도 그 등급 테이블은 어느 슬롯도 못 읽는다. */
  @Test
  void slot4_readsSensitiveButNotAllowlistLevel() {
    String sens = table("sen", "민감");
    String top = table("top", "기밀");
    sync.syncTenant();
    assertThat(selectAs(4, sens)).isNull();
    assertThat(selectAs(10, top)).isEqualTo("42501");
    assertThat(selectAs(4, top)).isEqualTo("42501");
  }

  /** 슬롯 롤은 쓰기 권한이 없다(기존 스크립트의 직접 INSERT 는 실패한다). search_path 가 테넌트 스키마라 비한정 이름으로 쓴다. */
  @Test
  void slotRole_cannotWrite() throws SQLException {
    String pub = table("pub", "공개");
    sync.syncTenant();
    assertThat(selectAs(10, pub)).as("대조군: 읽기는 된다").isNull();
    String role = TenantPipelineRole.pythonReadRoleName(DEFAULT_TEST_TENANT_ID, 10);
    String pw = TenantPipelineRole.pythonReadPassword(DEFAULT_TEST_TENANT_ID, 10, secret);
    try (Connection c =
            DriverManager.getConnection(PostgresTestContainer.INSTANCE.getJdbcUrl(), role, pw);
        Statement s = c.createStatement()) {
      assertThatThrownBy(() -> s.execute("INSERT INTO \"" + pub + "\" (v) VALUES ('x')"))
          .isInstanceOf(SQLException.class)
          .extracting(e -> ((SQLException) e).getSQLState())
          .isEqualTo("42501");
    }
  }

  /** 등급을 올리면 다음 동기화가 낮은 슬롯 GRANT 를 회수한다(과권한 창이 동기화로 닫힌다). */
  @Test
  void raisingLevel_thenSync_revokesLowerSlots() {
    String t = table("mv", "공개");
    sync.syncTenant();
    assertThat(selectAs(1, t)).isNull();
    long id = datasets.get(datasets.size() - 1);
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.update(DATASET)
                .set(DATASET.SECURITY_LEVEL_ID, fx.levelId("민감"))
                .where(DATASET.ID.eq(id))
                .execute());
    sync.syncDataset(id);
    assertThat(selectAs(1, t)).isEqualTo("42501");
    assertThat(selectAs(3, t)).isNull();
  }

  /** 손으로 건 과권한(드리프트)도 회수한다. */
  @Test
  void strayGrant_isRevokedBySyncTenant() {
    String sens = table("sen", "민감");
    sync.syncTenant();
    String s1 = TenantPipelineRole.pythonReadRoleName(DEFAULT_TEST_TENANT_ID, 1);
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> dsl.execute("GRANT SELECT ON " + DataSchema.qualify(sens) + " TO " + s1));
    assertThat(selectAs(1, sens)).isNull();
    PythonReadGrantSync.SyncResult r = sync.syncTenant();
    assertThat(selectAs(1, sens)).isEqualTo("42501");
    assertThat(r.revokeFailedTables()).doesNotContain(sens);
  }

  /** REPLACE 맞바꿈(RENAME)은 ACL 을 잃는다 — syncTable 로 복원된다(Task 4 는 이것을 훅으로 자동화한다). */
  @Test
  void swapLosesGrants_syncTableRestores() {
    String pub = table("pub", "공개");
    sync.syncTenant();
    dataTableService.createTempTable(pub);
    dsl.execute("INSERT INTO " + DataSchema.qualify(pub + "_tmp") + " (v) VALUES ('new')");
    dataTableService.swapTable(pub);
    sync.syncTable(pub);
    assertThat(selectAs(1, pub)).isNull();
  }

  /**
   * 소유자가 아닌 테이블(app 소유)에 남은 과권한은 app_tenant 의 REVOKE 가 WARNING 만 내고 실패한다 — 재확인이 이를 회수 실패로 잡아 실행을
   * 거부해야 한다(조용한 과권한 금지). 재확인 블록을 지우면 이 테스트가 실패한다(변이).
   */
  @Test
  void revokeThatSilentlyNoOps_isDetected_andRunIsRefused() {
    String t = m + "_own";
    String s1 = TenantPipelineRole.pythonReadRoleName(DEFAULT_TEST_TENANT_ID, 1);
    ownerDsl().execute("CREATE TABLE " + ownerQualified(t) + " (v text)");
    // app_tenant 에 일부 권한을 줘서 REVOKE 가 오류가 아니라 WARNING 이 되게 한다(운영의 V86 이전 테이블과 같은 형태).
    ownerDsl().execute("GRANT SELECT ON " + ownerQualified(t) + " TO app_tenant");
    ownerDsl().execute("GRANT SELECT ON " + ownerQualified(t) + " TO " + s1);
    long id = fx.createDatasetRow(t, fx.levelId("민감"), owner);
    long internalUser = userAt("내부");
    try {
      PythonReadGrantSync.SyncResult r = sync.syncTenant();
      assertThat(r.revokeFailedTables()).contains(t);
      assertThatThrownBy(() -> sync.prepareForRun(clearanceResolver.resolve(internalUser)))
          .isInstanceOf(PythonReadAccessException.class);
    } finally {
      fx.deleteDatasetRow(id);
      ownerDsl().execute("DROP TABLE IF EXISTS " + ownerQualified(t));
    }
  }

  /**
   * 부족한 GRANT 만 있는(남는 권한 없는) 테이블에는 REVOKE 를 하지 않는다 — 런타임 롤이 소유하지도, 권한도 없는 옛 테이블에 REVOKE 를 던지면 오류가 나
   * 회수 실패로 오판되고 테넌트의 PYTHON 전체가 거부된다. GRANT 실패는 과소권한(안전)이라 실행은 계속된다.
   */
  @Test
  void missingGrantOnUnownedTable_isUnderGrantNotRevokeFailure() {
    String t = m + "_nopriv";
    ownerDsl().execute("CREATE TABLE " + ownerQualified(t) + " (v text)");
    // 기본 권한(ALTER DEFAULT PRIVILEGES)이 있더라도 app_tenant 권한을 확실히 0 으로 만든다.
    ownerDsl().execute("REVOKE ALL ON " + ownerQualified(t) + " FROM app_tenant");
    long id = fx.createDatasetRow(t, fx.levelId("공개"), owner);
    long internalUser = userAt("내부");
    try {
      PythonReadGrantSync.SyncResult r = sync.syncTenant();
      assertThat(r.revokeFailedTables()).doesNotContain(t);
      assertThat(sync.prepareForRun(clearanceResolver.resolve(internalUser))).isEqualTo(2);
      assertThat(selectAs(2, t)).as("GRANT 실패 = 과소권한").isEqualTo("42501");
    } finally {
      fx.deleteDatasetRow(id);
      ownerDsl().execute("DROP TABLE IF EXISTS " + ownerQualified(t));
    }
  }

  @Test
  void prepareForRun_returnsSlotOfRunAs_andFailsClosedWithoutRank() {
    long internalUser = userAt("내부");
    assertThat(sync.prepareForRun(clearanceResolver.resolve(internalUser))).isEqualTo(2);
    long topUser = userAt("기밀");
    assertThat(sync.prepareForRun(clearanceResolver.resolve(topUser))).isEqualTo(4);
    assertThatThrownBy(() -> sync.prepareForRun(Clearance.none(1L, DEFAULT_TEST_TENANT_ID)))
        .isInstanceOf(PythonReadAccessException.class);
  }

  /**
   * 실행 주체 테넌트와 TenantContext 테넌트가 다르면 슬롯을 계산하지 않고 거부한다(fail-closed). 컨텍스트는 테넌트 1(슬롯 롤 있음)이라 가드가 없으면
   * 남의 테넌트 자격으로 슬롯 2 가 나온다 — 가드 제거 시 이 테스트가 실패한다(변이). 컨텍스트가 비어도 같은 거부.
   */
  @Test
  void prepareForRun_rejectsTenantMismatch_andMissingContext() {
    Clearance own = clearanceResolver.resolve(userAt("내부"));
    Clearance foreign =
        new Clearance(
            own.userId(),
            987_654_321L,
            own.rank(),
            own.roleIds(),
            own.tenantAdmin(),
            own.permissions());
    assertThat(sync.prepareForRun(own)).as("대조군: 같은 테넌트면 슬롯 2").isEqualTo(2);
    assertThatThrownBy(() -> sync.prepareForRun(foreign))
        .isInstanceOf(PythonReadAccessException.class)
        .hasMessageContaining("테넌트");
    TenantContext.clear();
    try {
      assertThatThrownBy(() -> sync.prepareForRun(own))
          .isInstanceOf(PythonReadAccessException.class)
          .hasMessageContaining("테넌트");
    } finally {
      TenantContext.set(DEFAULT_TEST_TENANT_ID);
    }
  }

  /** 기본 USER 역할을 떼고 지정 등급 자격의 역할 하나만 가진 사용자. */
  private long userAt(String level) {
    long uid = fx.createUser("prs_u");
    users.add(uid);
    fx.removeUserRole(uid);
    long rid = fx.createRole("prs_r_" + System.nanoTime(), fx.levelId(level), "pipeline:read");
    roles.add(rid);
    fx.assignRole(uid, rid);
    return uid;
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    for (Long id : datasets) {
      try {
        datasetService.deleteDataset(id);
      } catch (Exception ignored) {
        // 정리 실패는 다음 정리에 맡긴다(테스트 판정과 무관).
      }
    }
    users.forEach(fx::deleteUser);
    roles.forEach(fx::deleteRole);
  }
}
