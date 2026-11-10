package com.smartfirehub.securitylevel.pythonread;

import static com.smartfirehub.jooq.Tables.DATASET;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.global.datastore.DataStoreDb;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantPipelineRole;
import com.smartfirehub.global.tenant.TenantPipelineRoleBootstrap;
import com.smartfirehub.securitylevel.event.DatasetSecurityLevelChangedEvent;
import com.smartfirehub.securitylevel.pythonread.PythonReadGrantBackend.NarrowResult;
import com.smartfirehub.securitylevel.repository.SecurityLevelRepository;
import com.smartfirehub.support.DataStorePostgresTestContainer;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.PythonReadTestTables;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 분리 설치({@code firehub.data-store.postgres.separate=true})의 커밋 직전 데이터셋 좁히기(WD-701 3차, 설계 결정 Q1 A).
 *
 * <p>예전(WD-624 리뷰 9)에는 분리 설치면 늘 "확인 못 함" 으로 데이터 저장소를 건드리지 않고 대체 경로(커밋 뒤 비동기 회수)로 갔다 — 커밋 응답 뒤에도 슬롯이
 * 그 테이블을 읽었다. 이제 커밋 직전에 데이터 저장소에서 회수하고, 그 회수가 <b>플랫폼 커밋보다 먼저</b> 데이터 저장소에 커밋된다.
 *
 * <ul>
 *   <li>이 트랜잭션에 데이터 저장소 연결이 아직 묶이지 않았으면 따로 빌린 연결에서 곧바로 커밋한다 — 이 트랜잭션에 새로 묶지 않는다(커밋 직전에 처음 묶은 연결은
 *       플랫폼 커밋 뒤에야 커밋되기 때문).
 *   <li>이미 묶였으면 그 연결에서 회수한다 — 묶인 연결의 커밋 직전 콜백이 플랫폼 커밋 전에 함께 커밋한다.
 * </ul>
 *
 * 권한은 데이터 저장소(두 번째 컨테이너)에 슈퍼유저로 붙어 {@code has_table_privilege} 로 본다 — 이 클래스는 시스템 속성 없이 늘 분리 모드라
 * {@code PythonReadTestTables.selectAs} 의 URL 판정({@code PostgresTestContainer.dataStoreJdbcUrl})을 쓸
 * 수 없다.
 */
class PythonReadGrantSplitModeTest extends IntegrationTestBase {

  @DynamicPropertySource
  static void separateDataStoreProperties(DynamicPropertyRegistry registry) {
    DataStorePostgresTestContainer.registerSeparateDataStoreProperties(registry);
  }

  @Autowired private PythonReadGrants grants;
  @Autowired private PythonReadGrantSync sync;
  @Autowired private DSLContext dsl;
  @Autowired @DataStoreDb private DSLContext dataDsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private DatasetService datasetService;
  @Autowired private TenantPipelineRoleBootstrap roleBootstrap;
  @Autowired private PythonReadGrantListener listener;
  @Autowired private PythonReadSessionTerminator terminator;
  @Autowired private SecurityLevelRepository levelRepository;
  @Autowired private DatasetRepository datasetRepository;
  @Autowired private PlatformTransactionManager txManager;
  @Autowired private PythonReadSyncExecutor syncExecutor;

  private SecurityFixture fx;
  private final List<Long> datasets = new ArrayList<>();
  private long owner;
  private String m;

  @BeforeEach
  void setUp() {
    // 시스템 속성 없는 분리 모드라 IntegrationTestBase 가 데이터 저장소에 테넌트 롤을 만들지 않는다 — 운영 경로(기동 치유)로 만든다(멱등).
    roleBootstrap.healTenant(DEFAULT_TEST_TENANT_ID);
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    owner = fx.createUser("prsm_owner");
    m = "prsm" + System.nanoTime();
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
  }

  @AfterEach
  void tearDown() {
    sync.preCommitBoundsForTest(PreCommitDeadline.DEFAULT_BOUNDS);
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    for (Long id : datasets) {
      try {
        datasetService.deleteDataset(id);
      } catch (Exception ignored) {
        // 정리 실패는 다음 정리에 맡긴다(테스트 판정과 무관).
      }
    }
    fx.deleteUser(owner);
  }

  /** 등급을 지정한 물리 테이블(행 1개)을 만들고 슬롯 권한을 그 등급에 맞춘다. */
  private long table(String suffix, String level) {
    String t = m + "_" + suffix;
    long id =
        new PythonReadTestTables(
                datasetService,
                dsl,
                dataDsl,
                fixtureTransactionTemplate,
                fx,
                this::awaitPythonReadGrantSync)
            .create(t, level, owner);
    datasets.add(id);
    sync.syncTenant();
    return id;
  }

  private String tableName(long id) {
    return TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.select(DATASET.TABLE_NAME)
                .from(DATASET)
                .where(DATASET.ID.eq(id))
                .fetchOne(0, String.class));
  }

  /** 데이터 저장소에서 지금 그 테이블에 SELECT 를 가진 슬롯 번호들(슈퍼유저 연결 — 커밋된 상태만 본다). */
  private static Set<Integer> slotsWithSelect(String table) throws SQLException {
    Set<Integer> slots = new TreeSet<>();
    String qualified = DataSchema.forTenant(DEFAULT_TEST_TENANT_ID) + ".\"" + table + "\"";
    try (Connection c = DataStorePostgresTestContainer.openSuperuserConnection();
        PreparedStatement ps = c.prepareStatement("select has_table_privilege(?, ?, 'SELECT')")) {
      for (int k = 1; k <= TenantPipelineRole.PYTHON_READ_SLOTS; k++) {
        ps.setString(1, TenantPipelineRole.pythonReadRoleName(DEFAULT_TEST_TENANT_ID, k));
        ps.setString(2, qualified);
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          if (rs.getBoolean(1)) {
            slots.add(k);
          }
        }
      }
    }
    return slots;
  }

  /** 데이터 저장소에서 슈퍼유저로 문장 하나를 실행한다(권한 어긋남 만들기용). */
  private static void dataStoreSuperuser(String sql) throws SQLException {
    try (Connection c = DataStorePostgresTestContainer.openSuperuserConnection();
        Statement st = c.createStatement()) {
      st.execute(sql);
    }
  }

  private static String slotRole(int k) {
    return "\"" + TenantPipelineRole.pythonReadRoleName(DEFAULT_TEST_TENANT_ID, k) + "\"";
  }

  private static String qualified(String table) {
    return DataSchema.forTenant(DEFAULT_TEST_TENANT_ID) + ".\"" + table + "\"";
  }

  /** 테넌트 트랜잭션에서 등급을 바꾸고 커밋 직전 좁히기를 부른다 — {@code inTx} 는 좁히기 뒤 같은 트랜잭션(커밋 전)에서 돈다. */
  private NarrowResult changeAndNarrow(
      long id, long from, long to, boolean bindDataStoreFirst, ThrowingRunnable inTx) {
    return TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> {
          dsl.update(DATASET).set(DATASET.SECURITY_LEVEL_ID, to).where(DATASET.ID.eq(id)).execute();
          if (bindDataStoreFirst) {
            dataDsl.fetch("select 1"); // 같은 트랜잭션에서 데이터 저장소를 이미 쓴 업무(테이블 생성·맞바꿈 등)를 흉내 낸다
          }
          Set<Object> before =
              new HashSet<>(TransactionSynchronizationManager.getResourceMap().keySet());
          NarrowResult r = grants.narrowDatasetInCurrentTx(id, from, to);
          assertThat(TransactionSynchronizationManager.getResourceMap().keySet())
              .as("좁히기가 이 트랜잭션에 데이터 저장소 연결을 새로 묶지 않는다")
              .isEqualTo(before);
          try {
            inTx.run();
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
          return r;
        });
  }

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws Exception;
  }

  /**
   * 핵심(설계 결정 Q1 A): 묶인 연결이 없으면 따로 빌린 데이터 저장소 연결에서 회수를 <b>플랫폼 커밋 전에 커밋</b>한다 — 발행 트랜잭션이 아직 열려 있는 동안
   * 다른 연결(슈퍼유저)에서 이미 슬롯 1·2 의 SELECT 가 사라진 것이 보인다. 확인됨 + 실제로 회수한 슬롯 {1,2}(커밋 뒤 그 슬롯만 끊는다). 고치기 전에는
   * 늘 "확인 못 함" 이라 커밋 응답 뒤에도 슬롯이 읽었다.
   */
  @Test
  void narrowing_unbound_revokesAndCommitsOnDataStoreBeforePlatformCommit() throws Exception {
    long id = table("own", "공개");
    String t = tableName(id);
    assertThat(slotsWithSelect(t)).as("전제: 공개 — 모든 슬롯이 읽는다").contains(1, 2, 3);

    NarrowResult result =
        changeAndNarrow(
            id,
            fx.levelId("공개"),
            fx.levelId("민감"),
            false,
            () ->
                assertThat(slotsWithSelect(t))
                    .as("플랫폼 커밋 전 — 데이터 저장소 회수는 이미 커밋됐다")
                    .doesNotContain(1, 2)
                    .contains(3));

    assertThat(result.confirmed()).isTrue();
    assertThat(result.revokedSlots()).isEqualTo(Set.of(1, 2));
    assertThat(result.needsFallback()).isFalse();
    assertThat(slotsWithSelect(t)).doesNotContain(1, 2).contains(3);
  }

  /**
   * 이미 묶인 데이터 저장소 연결이 있으면 그 연결에서 회수한다 — 플랫폼 커밋 전까지는 그 트랜잭션 안이라 다른 연결에는 아직 안 보이고, 묶인 연결의 커밋 직전
   * 콜백(플랫폼 커밋 전)이 함께 커밋한다. 같은 세션이라 그 트랜잭션이 쥔 잠금에 막히지 않는다.
   */
  @Test
  void narrowing_bound_revokesOnBoundConnection_committedWithItBeforePlatformCommit()
      throws Exception {
    long id = table("bnd", "공개");
    String t = tableName(id);

    NarrowResult result =
        changeAndNarrow(
            id,
            fx.levelId("공개"),
            fx.levelId("민감"),
            true,
            () ->
                assertThat(slotsWithSelect(t))
                    .as("묶인 연결의 회수는 아직 커밋 전(같은 데이터 저장소 트랜잭션)")
                    .contains(1, 2));

    assertThat(result.confirmed()).isTrue();
    assertThat(result.revokedSlots()).isEqualTo(Set.of(1, 2));
    assertThat(slotsWithSelect(t)).as("커밋 뒤").doesNotContain(1, 2).contains(3);
  }

  /**
   * WD-664 5 — 클론 상속·자동 상향은 변경 전 등급으로 기본 등급을 싣는다. 권한이 아직 없는 새 테이블이면 회수할 것이 없어 확인됨 + 회수한 슬롯 없음 — 대체
   * 경로(그 차이 슬롯 세션을 테넌트 전체에서 끊기)도, 확인 뒤 끊기도 없다. 고치기 전 분리 설치는 늘 "확인 못 함" 이라 차이 슬롯 {1,2} 세션을 끊었다.
   */
  @Test
  void cloneOrAutoRaise_onTableWithoutGrants_needsNoKillAtAll() throws Exception {
    long id = table("cln", "공개");
    String t = tableName(id);
    StringBuilder roles = new StringBuilder();
    for (int k = 1; k <= TenantPipelineRole.PYTHON_READ_SLOTS; k++) {
      roles.append(k == 1 ? "" : ", ").append(slotRole(k));
    }
    dataStoreSuperuser("REVOKE ALL ON " + qualified(t) + " FROM " + roles);

    NarrowResult result = changeAndNarrow(id, fx.levelId("공개"), fx.levelId("민감"), false, () -> {});

    assertThat(result.confirmed()).isTrue();
    assertThat(result.needsFallback()).isFalse();
    assertThat(result.needsKillAfterCommit()).as("회수한 슬롯이 없으면 아무 세션도 끊지 않는다").isFalse();
  }

  /**
   * WD-664 5 — 잠금을 못 잡아 확인하지 못하면, 이전 → 새 등급 차이에 <b>지금 실제로 SELECT 를 가진</b> 슬롯을 더해 막는다. 민감 → 기밀(허용
   * 목록) 차이는 {3..10} 이지만 손으로 건 슬롯 1 GRANT(ACL 이 이전 등급보다 넓게 어긋남)도 막는다. 고치기 전에는 차이만 막아 슬롯 1 이 빠졌다.
   */
  @Test
  void lockTimeout_blocksDiffPlusCurrentHolders_includingDriftedGrant() throws Exception {
    long id = table("drf", "민감");
    String t = tableName(id);
    dataStoreSuperuser("GRANT SELECT ON " + qualified(t) + " TO " + slotRole(1));
    try (Connection holder = DataStorePostgresTestContainer.openSuperuserConnection()) {
      holder.setAutoCommit(false);
      try (PreparedStatement ps =
          holder.prepareStatement("select pg_advisory_xact_lock(hashtextextended(?, 0))")) {
        ps.setString(1, PythonReadGrantSync.syncLockKey(DEFAULT_TEST_TENANT_ID));
        ps.execute();
      }
      NarrowResult result =
          changeAndNarrow(id, fx.levelId("민감"), fx.levelId("기밀"), false, () -> {});

      assertThat(result.confirmed()).isFalse();
      Set<Integer> expected = new TreeSet<>(Set.of(1));
      for (int k = 3; k <= TenantPipelineRole.PYTHON_READ_SLOTS; k++) {
        expected.add(k);
      }
      assertThat(result.exposedSlots()).as("차이 {3..10} + 어긋난 슬롯 1").isEqualTo(expected);
      holder.rollback();
    }
  }

  /**
   * 시간 예산(Tibero 커밋 직전 좁히기와 같은 장치)을 넘기면 회수하지 않고 "확인 못 함" + 새 등급에서 못 읽는 슬롯을 막는다 — 어디까지 회수했는지 모르기
   * 때문이다. 이 트랜잭션에 데이터 저장소를 묶지도 않는다. 공개 → 민감이라 예산 경로는 {1,2} 만 막는다 — 파사드의 예상 밖 예외 처리(모든 슬롯)와
   * 구별된다(WD-701 3차 리뷰 SF2: 예전 민감 → 기밀 판정은 두 경로 모두 전체 슬롯이라 구별하지 못했다). 실제 연결의 드라이버 문장 취소(SQLState
   * 57014)는 {@link #bound_statementCancelledAfterBudget_rollsBackToSavepoint_outerTxCommits} 가, 분류
   * 자체는 {@code PreCommitDeadlineTest} 가 본다.
   */
  @Test
  void budgetExceeded_isUnconfirmed_blockingOnlySlotsTheNewLevelCannotRead() throws Exception {
    long id = table("bdg", "공개");
    sync.preCommitBoundsForTest(
        new PreCommitDeadline.Bounds(
            Duration.ofNanos(1), Duration.ofNanos(1), Duration.ofNanos(1)));

    NarrowResult result = changeAndNarrow(id, fx.levelId("공개"), fx.levelId("민감"), false, () -> {});

    assertThat(result.confirmed()).isFalse();
    assertThat(result.exposedSlots()).as("예산 경로 — 파사드 catch-all 이면 전체 슬롯").isEqualTo(Set.of(1, 2));
    assertThat(slotsWithSelect(tableName(id))).as("회수하지 않았다").contains(1, 2, 3);
  }

  /**
   * WD-701 3차 리뷰 SF1 — 따로 빌리는 연결은 접속 상한까지만 기다린다. 데이터 저장소가 응답하지 않으면(여기선 getConnection 이 멈춘
   * DataSource) 풀 설정 대기(기본 30초)까지 플랫폼 커넥션을 쥐지 않고 접속 상한 뒤 "확인 못 함" + {1,2} 로 대체 경로에 맡긴다. 고치기 전에는
   * getConnection 이 상한 없이 막혀 이 테스트가 멈췄다(래치 해제 전까지).
   */
  @Test
  void ownConnection_borrowIsBoundedByConnectTimeout() throws Exception {
    long id = table("brw", "공개");
    CountDownLatch never = new CountDownLatch(1);
    DataSource stuck =
        (DataSource)
            Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("getConnection")) {
                    never.await(30, TimeUnit.SECONDS); // 죽은 데이터 저장소 — 상한 없이 멈춘다
                    throw new SQLException("unreachable");
                  }
                  if (method.getName().equals("isWrapperFor")) {
                    return false;
                  }
                  throw new UnsupportedOperationException(method.getName());
                });
    PythonReadGrantSync bounded =
        new PythonReadGrantSync(
            dataDsl,
            stuck,
            terminator,
            levelRepository,
            datasetRepository,
            txManager,
            syncExecutor,
            true);
    bounded.preCommitBoundsForTest(
        new PreCommitDeadline.Bounds(
            Duration.ofSeconds(5), Duration.ofMillis(300), Duration.ofSeconds(3)));
    try {
      long started = System.nanoTime();
      NarrowResult result =
          TenantRlsTestSupport.runInTenantTransaction(
              fixtureTransactionTemplate,
              DEFAULT_TEST_TENANT_ID,
              () -> bounded.narrowDatasetInCurrentTx(id, fx.levelId("공개"), fx.levelId("민감")));
      Duration took = Duration.ofNanos(System.nanoTime() - started);

      assertThat(took).as("접속 상한(300ms) 근처에서 돌아온다").isLessThan(Duration.ofSeconds(3));
      assertThat(result.confirmed()).isFalse();
      assertThat(result.exposedSlots()).isEqualTo(Set.of(1, 2));
      assertThat(slotsWithSelect(tableName(id))).contains(1, 2);
    } finally {
      never.countDown();
      bounded.shutdownConnectionBorrower();
    }
  }

  /**
   * WD-701 3차 리뷰 SF3 — 묶인 연결 경로에서 잠금을 다른 세션이 쥐어 확인하지 못하면 SAVEPOINT 로 되돌리고(데이터 저장소 트랜잭션을 aborted 로
   * 남기지 않는다) 바깥 트랜잭션은 정상 커밋된다: 등급이 저장되고, 같은 트랜잭션에서 데이터 저장소에 쓴 업무도 커밋되며, ACL 은 그대로다(대체 경로가 막는다).
   */
  @Test
  void bound_lockHeldElsewhere_rollsBackToSavepoint_outerTxCommits() throws Exception {
    long id = table("blk", "공개");
    String t = tableName(id);
    String marker = m + "_mk1";
    dataStoreSuperuser("CREATE TABLE public.\"" + marker + "\" (v int)");
    // 앱 데이터 저장소 계정이 같은 트랜잭션에서 쓸 수 있게(업무 쓰기 흉내).
    dataStoreSuperuser("GRANT INSERT ON public.\"" + marker + "\" TO PUBLIC");
    try (Connection holder = DataStorePostgresTestContainer.openSuperuserConnection()) {
      holder.setAutoCommit(false);
      try (PreparedStatement ps =
          holder.prepareStatement("select pg_advisory_xact_lock(hashtextextended(?, 0))")) {
        ps.setString(1, PythonReadGrantSync.syncLockKey(DEFAULT_TEST_TENANT_ID));
        ps.execute();
      }
      NarrowResult result =
          changeAndNarrow(
              id,
              fx.levelId("공개"),
              fx.levelId("민감"),
              true,
              () -> dataDsl.execute("insert into public.\"" + marker + "\" values (1)"));
      holder.rollback();

      assertThat(result.confirmed()).isFalse();
      assertThat(result.exposedSlots()).containsAll(Set.of(1, 2));
    }
    assertOuterTxCommitted(id, t, marker);
  }

  /**
   * WD-701 3차 리뷰 SF3 — 묶인 연결 경로에서 예산 초과(문장 실패)가 나도 SAVEPOINT 로 되돌려 바깥 트랜잭션은 정상 커밋된다(위와 같은 판정). 예산
   * 초과 표시는 첫 데이터 저장소 문장 전에 던져지므로 이 경로의 예외 처리(되돌리기 후 다시 던짐 → 예산 초과로 확인 못 함)를 지난다.
   */
  @Test
  void bound_budgetExceeded_rollsBackToSavepoint_outerTxCommits() throws Exception {
    long id = table("bbg", "공개");
    String t = tableName(id);
    String marker = m + "_mk2";
    dataStoreSuperuser("CREATE TABLE public.\"" + marker + "\" (v int)");
    // 앱 데이터 저장소 계정이 같은 트랜잭션에서 쓸 수 있게(업무 쓰기 흉내).
    dataStoreSuperuser("GRANT INSERT ON public.\"" + marker + "\" TO PUBLIC");
    sync.preCommitBoundsForTest(
        new PreCommitDeadline.Bounds(
            Duration.ofNanos(1), Duration.ofNanos(1), Duration.ofNanos(1)));
    NarrowResult result =
        changeAndNarrow(
            id,
            fx.levelId("공개"),
            fx.levelId("민감"),
            true,
            () -> dataDsl.execute("insert into public.\"" + marker + "\" values (1)"));

    assertThat(result.confirmed()).isFalse();
    assertThat(result.exposedSlots()).isEqualTo(Set.of(1, 2));
    assertOuterTxCommitted(id, t, marker);
  }

  /**
   * WD-701 3차 리뷰 SF3(+SF2 의 실제 연결 57014) — 묶인 연결에서 문장이 <b>실제로</b> 실패해 데이터 저장소 트랜잭션이 aborted 가 되는
   * 경우. 다른 세션이 테이블에 ACCESS EXCLUSIVE 를 쥐어 ACL 잠금(LOCK TABLE, lock_timeout 5초)이 문장 상한(queryTimeout
   * 2초)으로 취소되고(57014), 그때 예산(1.5초)도 넘겨 안쪽 savepoint 되돌리기조차 예산 검사에 막힌다 — 데이터 저장소 트랜잭션이 aborted 로
   * 남는다. 바깥 {@code python_read_narrow} SAVEPOINT 로 되돌려야만 같은 트랜잭션의 데이터 저장소 쓰기·등급 변경이 커밋된다. 결과는 예산
   * 경로의 "확인 못 함" {1,2}. 변이(SAVEPOINT 되돌리기 제거)면 aborted 트랜잭션 때문에 이 테스트가 실패한다(위 두 테스트는 문장 실패가 없어 변이에도
   * 통과하는 회귀 방어용이다).
   */
  @Test
  void bound_statementCancelledAfterBudget_rollsBackToSavepoint_outerTxCommits() throws Exception {
    long id = table("bcn", "공개");
    String t = tableName(id);
    String marker = m + "_mk3";
    dataStoreSuperuser("CREATE TABLE public.\"" + marker + "\" (v int)");
    // 앱 데이터 저장소 계정이 같은 트랜잭션에서 쓸 수 있게(업무 쓰기 흉내).
    dataStoreSuperuser("GRANT INSERT ON public.\"" + marker + "\" TO PUBLIC");
    sync.preCommitBoundsForTest(
        new PreCommitDeadline.Bounds(
            Duration.ofMillis(1500), Duration.ofSeconds(1), Duration.ofSeconds(3)));
    try (Connection holder = DataStorePostgresTestContainer.openSuperuserConnection()) {
      holder.setAutoCommit(false);
      try (Statement st = holder.createStatement()) {
        st.execute("LOCK TABLE " + qualified(t) + " IN ACCESS EXCLUSIVE MODE");
      }
      NarrowResult result =
          changeAndNarrow(
              id,
              fx.levelId("공개"),
              fx.levelId("민감"),
              true,
              () -> dataDsl.execute("insert into public.\"" + marker + "\" values (1)"));
      holder.rollback();

      assertThat(result.confirmed()).isFalse();
      assertThat(result.exposedSlots()).isEqualTo(Set.of(1, 2));
    }
    assertOuterTxCommitted(id, t, marker);
  }

  /** 바깥 트랜잭션이 커밋됐다 — 등급이 민감으로 저장되고, 같은 트랜잭션의 데이터 저장소 쓰기가 남고, ACL(슬롯 1·2 SELECT)은 그대로다. */
  private void assertOuterTxCommitted(long id, String table, String marker) throws SQLException {
    Long level =
        TenantRlsTestSupport.runInTenantTransaction(
            fixtureTransactionTemplate,
            DEFAULT_TEST_TENANT_ID,
            () ->
                dsl.select(DATASET.SECURITY_LEVEL_ID)
                    .from(DATASET)
                    .where(DATASET.ID.eq(id))
                    .fetchOne(0, Long.class));
    assertThat(level).as("등급 변경이 커밋됐다").isEqualTo(fx.levelId("민감"));
    try (Connection c = DataStorePostgresTestContainer.openSuperuserConnection();
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select count(*) from public.\"" + marker + "\"")) {
      rs.next();
      assertThat(rs.getInt(1)).as("같은 트랜잭션의 데이터 저장소 쓰기도 커밋됐다").isEqualTo(1);
    } finally {
      dataStoreSuperuser("DROP TABLE IF EXISTS public.\"" + marker + "\"");
    }
    assertThat(slotsWithSelect(table)).as("되돌렸으니 ACL 은 그대로").contains(1, 2, 3);
  }

  /**
   * WD-701 3차 리뷰 B1 — 분리 설치는 테넌트 잠금이 데이터 저장소 회수와 함께 플랫폼 커밋 <b>전에</b> 풀린다. 그 틈에 옛 등급(공개)을 읽은 동기화가
   * 회수한 슬롯 1 에 다시 GRANT 하는 경쟁을 흉내 낸다(리스너 뒤, 플랫폼 커밋 전 슈퍼유저 GRANT). 커밋 뒤 회수 필수 뒷정리가 커밋된 새 등급(민감)으로 다시
   * 회수해, 실행기가 쉬면 슬롯 1 에 SELECT 가 없다. 고치기 전에는 확인된 결과에 끊기 요청만 해 슬롯 1 GRANT 가 남았다.
   */
  @Test
  void separateNarrowing_regrantInCommitWindow_isRevokedByAfterCommitCleanup() throws Exception {
    long id = table("rce", "공개");
    String t = tableName(id);
    long from = fx.levelId("공개");
    long to = fx.levelId("민감");

    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> {
          dsl.update(DATASET).set(DATASET.SECURITY_LEVEL_ID, to).where(DATASET.ID.eq(id)).execute();
          listener.narrowDatasetBeforeCommit(
              new DatasetSecurityLevelChangedEvent(
                  DEFAULT_TEST_TENANT_ID,
                  id,
                  from,
                  to,
                  DatasetSecurityLevelChangedEvent.Cause.MANUAL));
          try {
            assertThat(slotsWithSelect(t)).as("회수는 플랫폼 커밋 전에 커밋됐다").doesNotContain(1, 2);
            dataStoreSuperuser("GRANT SELECT ON " + qualified(t) + " TO " + slotRole(1));
            assertThat(slotsWithSelect(t)).as("경쟁 재현: 옛 등급으로 다시 GRANT").contains(1);
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
          return null;
        });

    assertThat(syncExecutor.awaitIdle(Duration.ofSeconds(30))).isTrue();
    assertThat(slotsWithSelect(t)).as("커밋 뒤 뒷정리가 새 등급으로 다시 회수했다").doesNotContain(1, 2).contains(3);
  }

  /** 넓어지는 변경(민감 → 공개)은 분리 설치에서도 회수할 것이 없어 확인됨 — 대체 경로가 아무것도 끊지 않는다. */
  @Test
  void wideningChange_inSplitMode_needsNoFallback() throws Exception {
    long id = table("wid", "민감");

    NarrowResult result = changeAndNarrow(id, fx.levelId("민감"), fx.levelId("공개"), false, () -> {});

    assertThat(result.needsFallback()).isFalse();
    assertThat(result.needsKillAfterCommit()).isFalse();
  }
}
