package com.smartfirehub.securitylevel.pythonread;

import static com.smartfirehub.jooq.Tables.DATASET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.global.datastore.DataStoreDb;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantPipelineRole;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.repository.SecurityLevelRepository;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.PostgresTestContainer;
import com.smartfirehub.support.PythonReadTestTables;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * PythonReadGrantSync 실측(스펙 §4.3, WD-29) — "GRANT 문을 실행했다"가 아니라 슬롯 롤로 <b>실제 로그인해 SELECT</b> 한
 * 결과(성공/42501)를 단언한다. 테넌트 1 V133 시드: 공개(위치 1)·내부(2)·민감(3)·기밀(4, allowlist_required).
 */
class PythonReadGrantSyncTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;

  /** 데이터 저장소(data 스키마) 테이블 조작·검사용 — 분리 모드에서는 플랫폼 DB 와 다른 DB 다(WD-494). */
  @Autowired @DataStoreDb private DSLContext dataDsl;

  @Autowired private PasswordEncoder encoder;
  @Autowired private DatasetService datasetService;
  @Autowired private DataTableService dataTableService;
  @Autowired private PythonReadGrantSync sync;
  @Autowired private ClearanceResolver clearanceResolver;
  @Autowired private PythonReadSessionTerminator terminator;
  @Autowired private SecurityLevelRepository levelRepository;
  @Autowired private DatasetRepository datasetRepository;
  @Autowired private PlatformTransactionManager txManager;
  @Autowired private PythonReadSyncExecutor syncExecutor;

  @Value("${firehub.data-store.postgres.separate:false}")
  private boolean separateDataStore;

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
    datasets.add(
        new PythonReadTestTables(
                datasetService,
                dsl,
                dataDsl,
                fixtureTransactionTemplate,
                fx,
                this::awaitPythonReadGrantSync)
            .create(t, level, owner));
    return t;
  }

  /** 슬롯 롤로 실제 로그인해 SELECT 한다. 권한 오류면 SQLState 를 돌려준다(성공이면 null). */
  private String selectAs(int slot, String table) {
    return PythonReadTestTables.selectAs(slot, table, secret);
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
            DriverManager.getConnection(PostgresTestContainer.dataStoreJdbcUrl(), role, pw);
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
        () -> dataDsl.execute("GRANT SELECT ON " + DataSchema.qualify(sens) + " TO " + s1));
    assertThat(selectAs(1, sens)).isNull();
    PythonReadGrantSync.SyncResult r = sync.syncTenant();
    assertThat(selectAs(1, sens)).isEqualTo("42501");
    assertThat(r.revokeFailedTables()).doesNotContain(sens);
  }

  /**
   * 뷰 우회(CR5) — 뷰는 소유자(app_tenant) 권한으로 바닥 테이블을 읽으므로, 슬롯 1 롤에 손으로 건 뷰 SELECT 는 민감 테이블을 우회해 읽게 한다.
   * 동기화는 데이터셋이 아닌 관계(뷰 포함)의 슬롯 GRANT 를 회수해야 한다. 변이: 수집 relkind 를 ('r','p') 로 되돌리면 회수 단언이 실패한다.
   */
  @Test
  void strayGrantOnView_isRevokedBySync() {
    String sens = table("sen", "민감");
    String view = m + "_v";
    String s1 = TenantPipelineRole.pythonReadRoleName(DEFAULT_TEST_TENANT_ID, 1);
    sync.syncTenant();
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> {
          dataDsl.execute(
              "CREATE VIEW "
                  + DataSchema.qualify(view)
                  + " AS SELECT v FROM "
                  + DataSchema.qualify(sens));
          dataDsl.execute("GRANT SELECT ON " + DataSchema.qualify(view) + " TO " + s1);
        });
    try {
      assertThat(selectAs(1, sens)).as("대조군: 테이블 직접 읽기는 거부").isEqualTo("42501");
      assertThat(selectAs(1, view)).as("전제: 뷰로는 우회해 읽힌다").isNull();
      PythonReadGrantSync.SyncResult r = sync.syncTenant();
      assertThat(selectAs(1, view)).isEqualTo("42501");
      assertThat(r.revokeFailedTables()).doesNotContain(view);
    } finally {
      TenantRlsTestSupport.runInTenantTransaction(
          fixtureTransactionTemplate,
          DEFAULT_TEST_TENANT_ID,
          () -> dataDsl.execute("DROP VIEW IF EXISTS " + DataSchema.qualify(view)));
    }
  }

  /** REPLACE 맞바꿈(RENAME)은 ACL 을 잃는다 — syncTable 로 복원된다(Task 4 는 이것을 훅으로 자동화한다). */
  @Test
  void swapLosesGrants_syncTableRestores() {
    String pub = table("pub", "공개");
    sync.syncTenant();
    String tmp = dataTableService.createTempTable(pub);
    dataDsl.execute("INSERT INTO " + DataSchema.qualify(tmp) + " (v) VALUES ('new')");
    dataTableService.swapTable(pub, tmp);
    sync.syncTable(pub);
    assertThat(selectAs(1, pub)).isNull();
  }

  /**
   * 소유자가 아닌 테이블(app 소유)에 남은 과권한은 app_tenant 의 REVOKE 가 WARNING 만 내고 실패한다 — 재확인이 이를 회수 실패로 잡아, 그
   * 과권한이 남은 <b>슬롯 1 롤로 실행하는</b> 주체(공개)는 거부해야 한다(조용한 과권한 금지). 다른 슬롯(내부=2)으로 실행하는 주체는 그 롤을 쓰지 않으므로 계속
   * 실행된다(Task 3 리뷰 후속 — 테이블 하나의 회수 실패가 테넌트 PYTHON 전체를 멈추지 않게). 변이: 재확인 블록 제거 → 공개 거부 단언 실패, 슬롯 필터
   * 제거(회수 실패 하나라도 있으면 거부) → 내부 슬롯 2 단언 실패.
   */
  @Test
  void revokeThatSilentlyNoOps_refusesOnlyTheOverGrantedSlot() {
    String t = m + "_own";
    String s1 = TenantPipelineRole.pythonReadRoleName(DEFAULT_TEST_TENANT_ID, 1);
    ownerDsl().execute("CREATE TABLE " + ownerQualified(t) + " (v text)");
    // app_tenant 에 일부 권한을 줘서 REVOKE 가 오류가 아니라 WARNING 이 되게 한다(운영의 V86 이전 테이블과 같은 형태).
    ownerDsl().execute("GRANT SELECT ON " + ownerQualified(t) + " TO app_tenant");
    ownerDsl().execute("GRANT SELECT ON " + ownerQualified(t) + " TO " + s1);
    long id = fx.createDatasetRow(t, fx.levelId("민감"), owner);
    long publicUser = userAt("공개");
    long internalUser = userAt("내부");
    try {
      PythonReadGrantSync.SyncResult r = sync.syncTenant();
      assertThat(r.revokeFailedTables()).contains(t);
      assertThat(r.revokeFailed().get(t)).containsExactly(s1);
      assertThatThrownBy(() -> sync.prepareForRun(clearanceResolver.resolve(publicUser)))
          .isInstanceOf(PythonReadAccessException.class)
          .hasMessageContaining("회수하지 못한");
      assertThat(sync.prepareForRun(clearanceResolver.resolve(internalUser)))
          .as("슬롯 2 롤에는 과권한이 없다 — 실행 계속")
          .isEqualTo(2);
    } finally {
      fx.deleteDatasetRow(id);
      ownerDsl().execute("DROP TABLE IF EXISTS " + ownerQualified(t));
    }
  }

  /**
   * REVOKE 문장 자체가 오류로 되돌려지는 경우(app_tenant 에 권한이 전혀 없는 app 소유 테이블) — 그 테이블은 재확인 대상(changedTables)에도
   * 들지 않으므로 savepoint 분기가 과권한 롤(excess)을 기록해야 한다. 슬롯 1 실행 주체(공개)는 거부, 슬롯 2(내부)는 통과. 변이: savepoint
   * 분기의 excess 기록 제거 → 공개 거부 단언 실패.
   */
  @Test
  void revokeThatErrors_recordsExcessRoles_andRefusesOnlyThatSlot() {
    String t = m + "_noacl";
    String s1 = TenantPipelineRole.pythonReadRoleName(DEFAULT_TEST_TENANT_ID, 1);
    ownerDsl().execute("CREATE TABLE " + ownerQualified(t) + " (v text)");
    ownerDsl().execute("REVOKE ALL ON " + ownerQualified(t) + " FROM app_tenant");
    ownerDsl().execute("GRANT SELECT ON " + ownerQualified(t) + " TO " + s1);
    long id = fx.createDatasetRow(t, fx.levelId("민감"), owner);
    long publicUser = userAt("공개");
    long internalUser = userAt("내부");
    try {
      PythonReadGrantSync.SyncResult r = sync.syncTenant();
      assertThat(r.revokeFailed().get(t)).contains(s1);
      assertThatThrownBy(() -> sync.prepareForRun(clearanceResolver.resolve(publicUser)))
          .isInstanceOf(PythonReadAccessException.class)
          .hasMessageContaining("회수하지 못한");
      assertThat(sync.prepareForRun(clearanceResolver.resolve(internalUser))).isEqualTo(2);
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

  /**
   * 실행 준비는 실행 슬롯 롤 하나만 맞춘다(CR3) — 그 슬롯에 손으로 건 과권한은 실행 전에 회수되고(fail-closed: 실행 슬롯에 과권한이 남은 채 실행되지
   * 않는다), 이 실행이 쓰지 않는 다른 슬롯의 과권한은 건드리지 않는다(전체 동기화가 회수). 변이: 실행 슬롯 한정 제거(전 슬롯 동기화) → 슬롯 1 잔존 단언 실패,
   * 실행 준비의 동기화 제거 → 슬롯 2 회수 단언 실패.
   */
  @Test
  void prepareForRun_syncsOnlyTheRunSlot_revokingItsStrayGrant() {
    String sens = table("sen", "민감");
    sync.syncTenant();
    String s1 = TenantPipelineRole.pythonReadRoleName(DEFAULT_TEST_TENANT_ID, 1);
    String s2 = TenantPipelineRole.pythonReadRoleName(DEFAULT_TEST_TENANT_ID, 2);
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dataDsl.execute(
                "GRANT SELECT ON " + DataSchema.qualify(sens) + " TO " + s1 + ", " + s2));
    assertThat(selectAs(2, sens)).as("전제: 슬롯 2 과권한").isNull();
    long internalUser = userAt("내부");
    assertThat(sync.prepareForRun(clearanceResolver.resolve(internalUser))).isEqualTo(2);
    assertThat(selectAs(2, sens)).as("실행 슬롯의 과권한은 실행 전에 회수").isEqualTo("42501");
    assertThat(selectAs(1, sens)).as("다른 슬롯은 실행 준비가 건드리지 않는다").isNull();
    sync.syncTenant();
    assertThat(selectAs(1, sens)).as("전체 동기화가 회수").isEqualTo("42501");
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

  /**
   * 회귀(WD-629): 동기화는 ACL 을 바꿀 테이블마다 ACCESS SHARE 를 잡고 트랜잭션 끝까지 쥔다. 그 순서가 해시 순서(HashMap)면 비결정이라 "테이블
   * 잠금은 이름 오름차순" 규칙을 세울 수 없다. 이름이 앞선 테이블 L 을 다른 연결이 ACCESS EXCLUSIVE 로 쥔 동안 동기화가 L 을 기다리면, 이름이 뒤인
   * 테이블 G 의 잠금은 아직 잡지 않았어야 한다. 이름 쌍을 무작위로 20번 바꿔 본다 — 해시 순서면 대략 절반에서 G 를 먼저 잡아 실패한다(변이: 정렬 제거).
   */
  @Test
  void syncTenant_locksTablesInNameOrder() throws Exception {
    String s1 = TenantPipelineRole.pythonReadRoleName(DEFAULT_TEST_TENANT_ID, 1);
    java.util.Random random = new java.util.Random();
    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newSingleThreadExecutor();
    try {
      for (int round = 0; round < 20; round++) {
        String x = m + "_lk" + randomLetters(random, 6);
        String y = m + "_lk" + randomLetters(random, 6);
        if (x.equals(y)) {
          continue;
        }
        String first = x.compareTo(y) < 0 ? x : y;
        String second = x.compareTo(y) < 0 ? y : x;
        // 데이터셋이 아닌 테이블 두 개에 슬롯 1 GRANT 를 걸어 둔다 — 동기화가 둘 다 REVOKE(ACL 변경)해야 한다.
        TenantRlsTestSupport.runInTenantTransaction(
            fixtureTransactionTemplate,
            DEFAULT_TEST_TENANT_ID,
            () -> {
              for (String t : List.of(first, second)) {
                dataDsl.execute("CREATE TABLE " + DataSchema.qualify(t) + " (v text)");
                dataDsl.execute("GRANT SELECT ON " + DataSchema.qualify(t) + " TO " + s1);
              }
            });
        try {
          java.util.concurrent.atomic.AtomicReference<
                  java.util.concurrent.Future<PythonReadGrantSync.SyncResult>>
              running = new java.util.concurrent.atomic.AtomicReference<>();
          dataDsl.transaction(
              cfg -> {
                DSLContext holder = cfg.dsl();
                holder.execute(
                    "LOCK TABLE " + DataSchema.qualify(first) + " IN ACCESS EXCLUSIVE MODE");
                running.set(
                    pool.submit(
                        () ->
                            TenantContext.runScopedGet(DEFAULT_TEST_TENANT_ID, sync::syncTenant)));
                Integer waiter = awaitRelationWaiter(holder, DataSchema.qualify(first));
                assertThat(waiter).as("동기화가 이름이 앞선 테이블 잠금을 기다려야 한다").isNotNull();
                Integer heldSecond =
                    holder
                        .fetchOne(
                            "SELECT count(*) FROM pg_locks WHERE pid = ? AND granted"
                                + " AND locktype = 'relation' AND relation = to_regclass(?)",
                            waiter,
                            DataSchema.qualify(second))
                        .get(0, Integer.class);
                assertThat(heldSecond)
                    .as("이름이 앞선 테이블을 기다리는 동안 뒤 테이블(%s) 잠금은 아직 없다 — 이름 오름차순", second)
                    .isZero();
              });
          PythonReadGrantSync.SyncResult r =
              running.get().get(30, java.util.concurrent.TimeUnit.SECONDS);
          assertThat(r.revokeFailedTables()).doesNotContain(first, second);
          assertThat(selectAs(1, first)).as("해제 뒤 회수됨").isEqualTo("42501");
          assertThat(selectAs(1, second)).as("해제 뒤 회수됨").isEqualTo("42501");
        } finally {
          TenantRlsTestSupport.runInTenantTransaction(
              fixtureTransactionTemplate,
              DEFAULT_TEST_TENANT_ID,
              () -> {
                dataDsl.execute("DROP TABLE IF EXISTS " + DataSchema.qualify(first));
                dataDsl.execute("DROP TABLE IF EXISTS " + DataSchema.qualify(second));
              });
        }
      }
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * 회귀(WD-655): 동기화가 테이블 잠금을 기다리는 사이 그 테이블이 RENAME 되면 잠금이 실패하고 옛 이름의 to_regclass 는 null 이다. 예전에는 이를
   * 「그새 지워짐 — ACL 도 사라짐」으로 보고 건너뛰었지만, RENAME 된 객체는 슬롯 GRANT 를 그대로 가져가 과권한이 남은 채 실행이
   * 통과했다(fail-open). 이제 남는 권한이 있던 슬롯 롤을 회수 실패로 세어 그 슬롯의 실행 준비를 거부하고(fail-closed), 다음 동기화가 새 이름의
   * GRANT 를 회수하면 다시 통과한다. 변이: 잠금 실패 + 테이블 없음 분기의 회수 실패 기록 제거 → 실행 준비가 통과해 실패.
   */
  @Test
  void lockFailedBecauseRenamed_countsExcessAsRevokeFailure_untilNextSync() throws Exception {
    String s1 = TenantPipelineRole.pythonReadRoleName(DEFAULT_TEST_TENANT_ID, 1);
    long publicUser = userAt("공개");
    Clearance publicClearance = clearanceResolver.resolve(publicUser);
    String t1 = m + "_rnsync";
    String r1 = m + "_rnsync_moved";
    String t2 = m + "_rnrun";
    String r2 = m + "_rnrun_moved";
    try {
      // 전체 동기화: 회수 실패에 옛 이름과 그 슬롯 롤이 들어간다.
      PythonReadGrantSync.SyncResult r =
          raceWithRename(t1, r1, s1, () -> sync.syncTenant()).get(30, TimeUnit.SECONDS);
      assertThat(r.revokeFailed().get(t1)).as("RENAME 으로 권한이 따라간 슬롯 롤").containsExactly(s1);

      // 실행 준비: 같은 경합이면 그 슬롯의 실행을 거부한다.
      java.util.concurrent.Future<Integer> run =
          raceWithRename(t2, r2, s1, () -> sync.prepareForRun(publicClearance));
      assertThatThrownBy(() -> run.get(30, TimeUnit.SECONDS))
          .hasCauseInstanceOf(PythonReadAccessException.class)
          .hasMessageContaining("회수하지 못한");

      // 다음 동기화가 새 이름의 GRANT 를 회수하면 거부가 풀린다.
      PythonReadGrantSync.SyncResult next = sync.syncTenant();
      assertThat(next.revokeFailedTables()).doesNotContain(r1, r2);
      assertThat(selectAs(1, r1)).as("새 이름의 과권한 회수").isEqualTo("42501");
      assertThat(selectAs(1, r2)).as("새 이름의 과권한 회수").isEqualTo("42501");
      assertThat(sync.prepareForRun(publicClearance)).isEqualTo(1);
    } finally {
      TenantRlsTestSupport.runInTenantTransaction(
          fixtureTransactionTemplate,
          DEFAULT_TEST_TENANT_ID,
          () -> {
            for (String t : List.of(t1, r1, t2, r2)) {
              dataDsl.execute("DROP TABLE IF EXISTS " + DataSchema.qualify(t));
            }
          });
    }
  }

  /**
   * 회귀(WD-673): 동기화가 테이블 잠금을 기다리는 사이 그 테이블이 실제로 DROP 되면 슬롯 GRANT 도 테이블과 함께 사라진다 — 과권한이 남지 않으므로 회수
   * 실패로 세지 않고, 그 동기화와 겹친 PYTHON 실행 준비도 거부하지 않는다. 예전에는 RENAME(권한이 따라감)과 같은 분기에서 회수 실패로 세어 실행이 한 번
   * 거부됐다. 진짜 회수 실패(RENAME·REVOKE 오류)는 {@link
   * #lockFailedBecauseRenamed_countsExcessAsRevokeFailure_untilNextSync}· {@link
   * #revokeThatErrors_recordsExcessRoles_andRefusesOnlyThatSlot} 가 계속 지킨다. 변이: 잠금 실패 분기의 oid 확인 제거
   * → 회수 실패에 테이블이 실리고 실행 준비가 거부돼 실패.
   */
  @Test
  void lockFailedBecauseDropped_isNotRevokeFailure() throws Exception {
    String s1 = TenantPipelineRole.pythonReadRoleName(DEFAULT_TEST_TENANT_ID, 1);
    long publicUser = userAt("공개");
    Clearance publicClearance = clearanceResolver.resolve(publicUser);
    String t1 = m + "_dropsync";
    String t2 = m + "_droprun";
    try {
      // 전체 동기화: 사라진 테이블은 회수 실패가 아니다.
      PythonReadGrantSync.SyncResult r =
          raceWithDdl(t1, "DROP TABLE " + DataSchema.qualify(t1), s1, () -> sync.syncTenant())
              .get(30, TimeUnit.SECONDS);
      assertThat(r.revokeFailedTables()).as("DROP 된 테이블은 회수 실패가 아니다").doesNotContain(t1);

      // 실행 준비: 같은 경합에서도 슬롯 1 실행을 거부하지 않는다.
      Integer slot =
          raceWithDdl(
                  t2,
                  "DROP TABLE " + DataSchema.qualify(t2),
                  s1,
                  () -> sync.prepareForRun(publicClearance))
              .get(30, TimeUnit.SECONDS);
      assertThat(slot).as("DROP 과 겹친 실행 준비는 통과한다").isEqualTo(1);
    } finally {
      TenantRlsTestSupport.runInTenantTransaction(
          fixtureTransactionTemplate,
          DEFAULT_TEST_TENANT_ID,
          () -> {
            for (String t : List.of(t1, t2)) {
              dataDsl.execute("DROP TABLE IF EXISTS " + DataSchema.qualify(t));
            }
          });
    }
  }

  /**
   * WD-655: 데이터셋이 아닌 테이블 {@code table} 에 슬롯 GRANT 를 걸고, 다른 연결이 그 테이블을 ACCESS EXCLUSIVE 로 쥔 동안
   * {@code action} (동기화)을 다른 스레드에서 시작한다. 동기화가 그 테이블 잠금을 기다리는 것을 pg_locks 로 확인한 뒤 같은 트랜잭션에서 {@code
   * renamed} 로 이름을 바꿔 커밋한다 — 동기화의 LOCK TABLE 은 옛 이름을 다시 찾지 못해 실패하고 옛 이름의 to_regclass 는 null 이
   * 된다(GRANT 는 새 이름으로 따라간다).
   */
  private <T> java.util.concurrent.Future<T> raceWithRename(
      String table, String renamed, String slotRole, java.util.function.Supplier<T> action) {
    return raceWithDdl(
        table,
        "ALTER TABLE " + DataSchema.qualify(table) + " RENAME TO \"" + renamed + "\"",
        slotRole,
        action);
  }

  /**
   * {@link #raceWithRename} 의 일반형(WD-673) — 동기화가 {@code table} 잠금을 기다리는 동안 잠금을 쥔 연결이 {@code ddl}
   * (RENAME·DROP 등)을 실행하고 커밋한다.
   */
  private <T> java.util.concurrent.Future<T> raceWithDdl(
      String table, String ddl, String slotRole, java.util.function.Supplier<T> action) {
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> {
          dataDsl.execute("CREATE TABLE " + DataSchema.qualify(table) + " (v text)");
          dataDsl.execute("GRANT SELECT ON " + DataSchema.qualify(table) + " TO " + slotRole);
        });
    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newSingleThreadExecutor();
    try {
      java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<T>> running =
          new java.util.concurrent.atomic.AtomicReference<>();
      dataDsl.transaction(
          cfg -> {
            DSLContext holder = cfg.dsl();
            holder.execute("LOCK TABLE " + DataSchema.qualify(table) + " IN ACCESS EXCLUSIVE MODE");
            running.set(
                pool.submit(() -> TenantContext.runScopedGet(DEFAULT_TEST_TENANT_ID, action)));
            assertThat(awaitRelationWaiter(holder, DataSchema.qualify(table)))
                .as("동기화가 %s 잠금을 기다려야 한다", table)
                .isNotNull();
            holder.execute(ddl);
          });
      return running.get();
    } finally {
      pool.shutdown();
    }
  }

  /**
   * WD-644 리뷰: 커밋 뒤 묶음 동기화에서 테이블 하나(b)의 읽기가 실패해도(PG 문장 오류 — 트랜잭션을 aborted 로 만든다) 다른 테이블(a·c)의 회수는
   * 커밋되고, 결과에는 b 만 영구 실패로 실린다. 테이블별 savepoint 를 지우면 b 의 오류가 묶음 트랜잭션 전체를 aborted 로 만들어 a 의 회수까지 커밋되지
   * 않고 묶음 전체가 실패한다(변이).
   */
  @Test
  void syncTablesWith_oneTableReadFailure_keepsOtherTablesRevokes_andReportsOnlyThatTable() {
    String a = table("a", "민감");
    String b = table("b", "민감");
    String c = table("c", "민감");
    sync.syncTenant();
    String s1 = TenantPipelineRole.pythonReadRoleName(DEFAULT_TEST_TENANT_ID, 1);
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dataDsl.execute(
                "GRANT SELECT ON "
                    + DataSchema.qualify(a)
                    + ", "
                    + DataSchema.qualify(c)
                    + " TO "
                    + s1));
    assertThat(selectAs(1, a)).as("전제: a 과권한").isNull();
    assertThat(selectAs(1, c)).as("전제: c 과권한").isNull();
    // b 의 데이터셋 행 읽기에서 PG 문장 오류(22012)를 내는 리포지토리 — 같은 트랜잭션을 aborted 로 만든다. 나머지는 진짜에 맡긴다.
    DatasetRepository failingForB = mock(DatasetRepository.class);
    when(failingForB.findTableLevels(any()))
        .thenAnswer(
            inv -> {
              String only = inv.getArgument(0);
              if (b.equals(only)) {
                dsl.execute("select 1/0");
              }
              return datasetRepository.findTableLevels(only);
            });
    PythonReadGrantSync isolated =
        new PythonReadGrantSync(
            dataDsl,
            terminator,
            levelRepository,
            failingForB,
            txManager,
            syncExecutor,
            separateDataStore);

    PythonReadSyncExecutor.AttemptResult result =
        PythonReadGrantBackend.retryableAttempt(
                DEFAULT_TEST_TENANT_ID,
                LoggerFactory.getLogger(PythonReadGrantSyncTest.class),
                "",
                isolated::syncTablesWith)
            .run(List.of(c, b, a), Duration.ofSeconds(5));

    assertThat(result)
        .as("b 만 실패(영구) — a·c 는 끝났다")
        .isEqualTo(new PythonReadSyncExecutor.PartialFailure(List.of(), List.of(b)));
    assertThat(selectAs(1, a)).as("a 의 회수는 커밋됐다").isEqualTo("42501");
    assertThat(selectAs(1, c)).as("b 뒤의 c 도 맞춰 커밋됐다").isEqualTo("42501");
  }

  /** 끊기가 늘 실패하는 끊기 장치(호출 수를 센다) — 소유자 풀 장애를 흉내 낸다. */
  private static PythonReadSessionTerminator failingTerminator(AtomicInteger kills) {
    return new PythonReadSessionTerminator(
        (tenant, slots) -> {
          kills.incrementAndGet();
          throw new IllegalStateException("끊기 실패 흉내");
        });
  }

  /** 주어진 끊기 장치로 만든 PG 동기화(나머지는 진짜 빈). */
  private PythonReadGrantSync syncWith(PythonReadSessionTerminator term) {
    return new PythonReadGrantSync(
        dataDsl,
        term,
        levelRepository,
        datasetRepository,
        txManager,
        syncExecutor,
        separateDataStore);
  }

  /**
   * 회수 미확인 뒷정리에서 회수 커밋 뒤의 두 번째 끊기가 실패하면 일시 실패로 끝나 실행기가 다시 한다(WD-664 1b). 예전에는 실패를 로그로 삼켜 DONE 이 되어,
   * 회수 커밋 전에 시작한 긴 쿼리를 끊지 못한 채 작업이 끝났다. 회수는 멱등이라 다시 해도 안전하다.
   */
  @Test
  void unconfirmedNarrowingCleanup_secondKillFails_isTransientAndRetried() throws Exception {
    table("sen", "민감");
    long datasetId = datasets.get(datasets.size() - 1);
    AtomicInteger kills = new AtomicInteger();
    PythonReadSessionTerminator term = failingTerminator(kills);
    try {
      PythonReadGrantSync isolated = syncWith(term);
      PythonReadSyncExecutor.Attempt attempt =
          PythonReadGrantBackend.waitingAttempt(
              DEFAULT_TEST_TENANT_ID,
              LoggerFactory.getLogger(PythonReadGrantSyncTest.class),
              "",
              () -> isolated.syncDatasetAfterUnconfirmedNarrowing(datasetId, Set.of(3), true));

      assertThat(attempt.run(List.of(String.valueOf(datasetId)), Duration.ZERO))
          .as("두 번째 끊기 실패는 일시 실패 — 실행기가 다시 한다")
          .isEqualTo(PythonReadSyncExecutor.Outcome.FAILED);
      assertThat(kills.get()).as("회수 앞뒤로 두 번 끊으려 했다").isEqualTo(2);

      // 실행기에서는 재시도 상한까지 다시 하며 매번 끊기를 다시 시도한다.
      kills.set(0);
      PythonReadSyncExecutor retrying =
          new PythonReadSyncExecutor(
              10,
              new PythonReadSyncExecutor.RetryPolicy(
                  Duration.ofMillis(5), Duration.ofMillis(20), 0, 3));
      try {
        assertThat(retrying.submitRetryable("wd664-1b", String.valueOf(datasetId), attempt))
            .isTrue();
        assertThat(retrying.awaitIdle(Duration.ofSeconds(30))).isTrue();
      } finally {
        retrying.shutdown();
      }
      assertThat(kills.get()).as("재시도마다 끊기를 다시 시도했다").isGreaterThan(2);
    } finally {
      term.shutdown();
    }
  }

  /**
   * 미확인 표식이 실행 슬롯과 무관한 슬롯에만 있으면 그 끊기 실패가 실행을 막지 않는다(WD-664 8). 예전에는 표식 슬롯 전부를 첫 끊기에 넘겨, 슬롯 3 의 끊기
   * 실패가 슬롯 1 실행까지 거부했다. 표식 슬롯의 실행은 여전히 막힌다(fail-closed).
   */
  @Test
  void prepareForRun_markerOnOtherSlot_killFailureDoesNotRejectUnrelatedRun() throws Exception {
    AtomicInteger kills = new AtomicInteger();
    PythonReadSessionTerminator term = failingTerminator(kills);
    try {
      term.markAndRequest(DEFAULT_TEST_TENANT_ID, Set.of(3));
      assertThat(term.awaitIdle(Duration.ofSeconds(5))).isTrue();
      assertThat(term.unconfirmedSlots(DEFAULT_TEST_TENANT_ID))
          .as("전제: 끊기 실패 → 슬롯 3 미확인 표식")
          .containsExactly(3);
      PythonReadGrantSync isolated = syncWith(term);

      assertThat(isolated.prepareForRun(clearanceResolver.resolve(userAt("공개"))))
          .as("슬롯 1 실행은 슬롯 3 표식의 끊기 실패와 무관하다")
          .isEqualTo(1);
      assertThat(term.unconfirmedSlots(DEFAULT_TEST_TENANT_ID))
          .as("다른 슬롯의 표식은 남는다")
          .containsExactly(3);

      Clearance sensitive = clearanceResolver.resolve(userAt("민감"));
      assertThatThrownBy(() -> isolated.prepareForRun(sensitive))
          .as("표식 슬롯(3)의 실행은 끊지 못하면 여전히 거부")
          .isInstanceOf(PythonReadAccessException.class);
    } finally {
      term.shutdown();
    }
  }

  /**
   * WD-664 9 — 커밋 콜백의 슬롯 세션 끊기({@link PythonReadGrantSync#terminateSlotSessionsAfterCommit})는 끊기가
   * 막혀 있어도(소유자 풀 바쁨) 기다리지 않고 돌아온다 — 커밋 콜백이 바깥 플랫폼 커넥션을 쥔 채 멈추지 않게. 표식은 돌아오기 전에 이미 서 있고, 막힘이 풀려 끊기가
   * 성공하면 지워진다. 고치기 전에는 상한(3초)만큼 기다렸다.
   */
  @Test
  void afterCommitTermination_doesNotWaitForBlockedKill_butMarksFirst() throws Exception {
    java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
    PythonReadSessionTerminator term =
        new PythonReadSessionTerminator(
            (tenant, slots) -> {
              try {
                gate.await(30, TimeUnit.SECONDS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              return 0;
            });
    try {
      PythonReadGrantSync isolated = syncWith(term);
      long start = System.nanoTime();
      isolated.terminateSlotSessionsAfterCommit(DEFAULT_TEST_TENANT_ID, Set.of(3));
      assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
          .as("막힌 끊기를 기다리지 않는다")
          .isLessThan(500L);
      assertThat(term.unconfirmedSlots(DEFAULT_TEST_TENANT_ID)).as("돌아오기 전에 표식").containsExactly(3);
      gate.countDown();
      assertThat(term.awaitIdle(Duration.ofSeconds(10))).isTrue();
      assertThat(term.unconfirmedSlots(DEFAULT_TEST_TENANT_ID)).as("늦게라도 끊기가 성공하면 지운다").isEmpty();
    } finally {
      gate.countDown();
      term.shutdown();
    }
  }

  /**
   * WD-664 9 — 기다리지 않게 된 뒤에도 대체 경로의 fail-closed 는 그대로다: 커밋 콜백의 끊기가 실패하면 표식이 남아, 그 슬롯의 실행 준비는 끊기를
   * 확인할 때까지 거부된다. 끊기가 되살아나면 실행 준비가 먼저 끊고 표식을 지운 뒤 실행한다.
   */
  @Test
  void afterCommitTermination_killFails_runRejectedUntilKillConfirmed() throws Exception {
    java.util.concurrent.atomic.AtomicBoolean failing =
        new java.util.concurrent.atomic.AtomicBoolean(true);
    PythonReadSessionTerminator term =
        new PythonReadSessionTerminator(
            (tenant, slots) -> {
              if (failing.get()) {
                throw new IllegalStateException("끊기 실패 흉내");
              }
              return 0;
            });
    try {
      PythonReadGrantSync isolated = syncWith(term);
      isolated.terminateSlotSessionsAfterCommit(DEFAULT_TEST_TENANT_ID, Set.of(3));
      assertThat(term.awaitIdle(Duration.ofSeconds(10))).isTrue();
      assertThat(term.unconfirmedSlots(DEFAULT_TEST_TENANT_ID))
          .as("끊기 실패 — 표식이 남는다")
          .containsExactly(3);

      Clearance sensitive = clearanceResolver.resolve(userAt("민감"));
      assertThatThrownBy(() -> isolated.prepareForRun(sensitive))
          .as("표식 슬롯(3)의 실행은 끊기를 확인할 때까지 거부(fail-closed)")
          .isInstanceOf(PythonReadAccessException.class);

      failing.set(false);
      assertThat(isolated.prepareForRun(sensitive)).as("끊기가 되면 실행한다").isEqualTo(3);
      assertThat(term.unconfirmedSlots(DEFAULT_TEST_TENANT_ID)).isEmpty();
    } finally {
      term.shutdown();
    }
  }

  /** 소문자 무작위 이름 조각. */
  private static String randomLetters(java.util.Random random, int length) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < length; i++) {
      sb.append((char) ('a' + random.nextInt(26)));
    }
    return sb.toString();
  }

  /**
   * 그 관계의 잠금을 기다리는(미승인) 다른 백엔드 pid 를 최대 10초 기다려 찾는다. pg_locks 만 본다(통계 뷰는 트랜잭션 안에서 스냅숏으로 고정된다 —
   * SqlScriptExecutorOutputLockTest 주석).
   */
  private static Integer awaitRelationWaiter(DSLContext holder, String qualified)
      throws InterruptedException {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      var row =
          holder.fetchOne(
              "SELECT pid FROM pg_locks WHERE NOT granted AND locktype = 'relation'"
                  + " AND relation = to_regclass(?) AND pid <> pg_backend_pid() LIMIT 1",
              qualified);
      if (row != null) {
        return row.get(0, Integer.class);
      }
      Thread.sleep(20);
    }
    return null;
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
