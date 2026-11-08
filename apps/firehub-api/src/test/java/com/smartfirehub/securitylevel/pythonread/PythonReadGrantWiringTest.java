package com.smartfirehub.securitylevel.pythonread;

import static com.smartfirehub.jooq.Tables.DATASET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.smartfirehub.dataset.dto.CloneDatasetRequest;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.DataStoreResolver;
import com.smartfirehub.global.datastore.DataStoreDb;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantPipelineRole;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.LevelPolicy;
import com.smartfirehub.securitylevel.dto.ChangeDatasetLevelRequest;
import com.smartfirehub.securitylevel.event.DatasetSecurityLevelChangedEvent;
import com.smartfirehub.securitylevel.event.SecurityLevelsChangedEvent;
import com.smartfirehub.securitylevel.repository.SecurityLevelRepository;
import com.smartfirehub.securitylevel.service.DatasetSecurityService;
import com.smartfirehub.securitylevel.service.SecurityLevelService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.PostgresTestContainer;
import com.smartfirehub.support.PythonReadTestTables;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * PYTHON 읽기 슬롯 GRANT 배선(스펙 §4.3, WD-29) — 테이블 훅(생성·클론·맞바꿈), syncTableAfterCommit 의 두 분기, 등급 이벤트
 * 리스너를 <b>수동 동기화 없이</b> 슬롯 롤 실접속 SELECT 결과로 검증한다. 테넌트 1 V133 시드: 공개(위치 1)·내부(2)·민감(3)·기밀(4).
 *
 * <p>이벤트 발행은 흐름 B 소유라(공통 결정 R2) 리스너 테스트는 {@link ApplicationEventPublisher} 로 <b>커밋되는 트랜잭션 안에서</b>
 * 직접 발행한다. 트랜잭션 밖 발행은 AFTER_COMMIT 리스너가 돌지 않는다.
 */
class PythonReadGrantWiringTest extends IntegrationTestBase {

  /** 발행 스레드의 "엉뚱한" 테넌트 — 리스너가 이 값이 아니라 event.tenantId() 로 동기화하는지 가른다. 존재하지 않는 id 다. */
  private static final long WRONG_AMBIENT_TENANT = 987_654_321L;

  @Autowired private DSLContext dsl;

  /** 플랫폼(@Primary) 커넥션 풀 — 회귀 테스트가 풀 크기만큼 동시 커밋을 만든다(WD-594). */
  @Autowired private DataSource dataSource;

  /** 데이터 저장소(data 스키마) 테이블 조작·검사용 — 분리 모드에서는 플랫폼 DB 와 다른 DB 다(WD-494). */
  @Autowired @DataStoreDb private DSLContext dataDsl;

  @Autowired private PasswordEncoder encoder;
  @Autowired private DatasetService datasetService;
  @Autowired private DataTableService dataTableService;
  @Autowired private PythonReadGrantSync sync;
  @Autowired private TiberoPythonReadGrantSync tiberoSync;
  @Autowired private org.springframework.transaction.PlatformTransactionManager txManager;

  /** 커밋 뒤 동기화 실행기 — DDL 겹침 회귀 테스트가 "아직 동기화 중인가"를 기다리지 않고 본다(WD-594). */
  @Autowired private PythonReadSyncExecutor syncExecutor;

  @Autowired private ApplicationEventPublisher events;
  @Autowired private SecurityLevelService levelService;
  @Autowired private SecurityLevelRepository levelRepository;
  @Autowired private DatasetSecurityService datasetSecurityService;

  @Value("${app.pipeline.role-password-secret}")
  private String secret;

  @Autowired
  @Qualifier("schemaOwnerDataSource")
  private DataSource schemaOwnerDataSource;

  private SecurityFixture fx;
  private final List<Long> datasets = new ArrayList<>();
  private long owner;
  private String m;

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    owner = fx.createUser("prw_owner");
    m = "prw" + System.nanoTime();
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
  }

  /**
   * 데이터셋 생성(기본 등급 = 내부) → 등급을 직접 지정(동기화 없이) + 행 1개. 생성 훅이 내부 기준 GRANT 를 걸었으므로 지정 등급과 ACL 이 어긋난 상태로
   * 돌려준다 — 리스너 테스트의 "고쳐야 할 상태"다.
   */
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

  private long lastDatasetId() {
    return datasets.get(datasets.size() - 1);
  }

  /** 슬롯 롤 10개의 SELECT 를 전부 걷는다(런타임 롤 = 테이블 소유자). "GRANT 가 없는 테이블" 출발 상태를 만든다. */
  private void revokeAllSlots(String table) {
    String roles =
        String.join(", ", TenantPipelineRole.pythonReadRoleNames(DEFAULT_TEST_TENANT_ID));
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> dataDsl.execute("REVOKE ALL ON " + DataSchema.qualify(table) + " FROM " + roles));
  }

  /** 슬롯 롤로 실제 로그인해 SELECT 한다. 권한 오류면 SQLState 를 돌려준다(성공이면 null). */
  private String selectAs(int slot, String table) {
    return PythonReadTestTables.selectAs(slot, table, secret);
  }

  /**
   * 발행 트랜잭션의 테넌트. 등급 정의 변경은 일부러 엉뚱하게 둔다 — 커밋 뒤 단계가 event.tenantId() 로 도는지 가른다. 데이터셋 등급 변경은 커밋 직전
   * 좁히기가 발행 트랜잭션의 RLS 테넌트로 읽으므로 이벤트 테넌트와 같아야 한다(다르면 리스너가 롤백시킨다 — {@link
   * #datasetLevelChanged_tenantMismatch_rollsBackPublishingTransaction}). 발행 측 서비스도 같은 컨텍스트로 이벤트를
   * 만든다.
   */
  private static long ambientFor(Object event) {
    return event instanceof DatasetSecurityLevelChangedEvent e
        ? e.tenantId()
        : WRONG_AMBIENT_TENANT;
  }

  /** 커밋(또는 롤백)되는 트랜잭션 안에서 이벤트를 발행한다(발행 테넌트는 {@link #ambientFor}). */
  private void publishInTx(Object event, boolean commit) {
    TenantContext.runScoped(
        ambientFor(event),
        () ->
            fixtureTransactionTemplate.executeWithoutResult(
                status -> {
                  events.publishEvent(event);
                  if (!commit) {
                    status.setRollbackOnly();
                  }
                }));
    // 등급 이벤트 리스너의 동기화는 커밋 뒤 전용 실행기에서 돈다(WD-624) — 비운 뒤 단언해야 롤백 "안 돌았음" 단언도 공허하지 않다.
    awaitPythonReadGrantSync();
  }

  // ---------------------------------------------------------------- 테이블 훅

  /** 맞바꿈(RENAME)은 ACL 을 잃는다 — 훅이 트랜잭션 밖 호출이면 즉시 복원한다(수동 동기화 없음). 훅 제거 시 실패(변이). */
  @Test
  void swapTable_restoresSlotGrantsWithoutManualSync() {
    String pub = table("pub", "공개");
    sync.syncTenant();
    assertThat(selectAs(1, pub)).as("대조군: 맞바꿈 전에는 읽는다").isNull();
    String tmp = dataTableService.createTempTable(pub);
    dataTableService.swapTable(pub, tmp); // 트랜잭션 밖 호출 → 즉시 실행 분기
    assertThat(selectAs(1, pub)).isNull();
  }

  /** 데이터셋 생성(트랜잭션 안 createTable) → 커밋 후 훅이 기본 등급(내부, 위치 2) 기준으로 GRANT 한다. */
  @Test
  void createDataset_grantsAfterCommitWithoutManualSync() {
    String t = table("new", "공개"); // table() 의 등급 지정은 동기화하지 않는다 → ACL 은 생성 시점(내부) 기준
    assertThat(selectAs(2, t)).isNull();
    assertThat(selectAs(1, t)).as("내부 기준이라 슬롯 1 은 못 읽는다").isEqualTo("42501");
  }

  /** 클론도 새 테이블이다(DataTableService.cloneTable — CTAS). 상속 여부와 무관하게 위치 2 이하이므로 슬롯 2 는 읽는다. */
  @Test
  void cloneDataset_grantsAfterCommit() {
    table("src", "공개");
    long srcId = lastDatasetId();
    String cloneName = m + "_cl";
    long cloneId =
        datasetService
            .cloneDataset(
                srcId, new CloneDatasetRequest(cloneName, cloneName, null, true, false), owner)
            .id();
    datasets.add(cloneId);
    String cloneTable =
        TenantRlsTestSupport.runInTenantTransaction(
            fixtureTransactionTemplate,
            DEFAULT_TEST_TENANT_ID,
            () ->
                dsl.select(DATASET.TABLE_NAME)
                    .from(DATASET)
                    .where(DATASET.ID.eq(cloneId))
                    .fetchSingle(DATASET.TABLE_NAME));
    awaitPythonReadGrantSync(); // 커밋 뒤 동기화는 전용 실행기에서 돈다(WD-594)
    assertThat(selectAs(2, cloneTable)).isNull();
  }

  // ------------------------------------------------- syncTableAfterCommit 두 분기

  /**
   * 트랜잭션 중 호출 → 커밋 전에는 아무것도 하지 않고 커밋 후에 GRANT 한다. "즉시 실행"으로 바꾸면 본문 안 단언이 실패한다(변이). 롤백되면 실행하지 않는다.
   */
  @Test
  void syncTableAfterCommit_inTransaction_runsOnlyAfterCommit() {
    String pub = table("atx", "공개");
    revokeAllSlots(pub);
    AtomicReference<String> insideBody = new AtomicReference<>("unset");

    // 롤백 → 실행되지 않는다.
    fixtureTransactionTemplate.executeWithoutResult(
        status -> {
          sync.syncTableAfterCommit(pub);
          status.setRollbackOnly();
        });
    awaitPythonReadGrantSync(); // 실행기에 넘겼다면 끝까지 기다린 뒤 본다 — "아직 안 돌았을 뿐" 으로 공허하게 통과하지 않게
    assertThat(selectAs(1, pub)).as("롤백된 트랜잭션의 예약은 실행되지 않는다").isEqualTo("42501");

    // 커밋 → 본문 안에서는 아직 없고, 커밋 후 생긴다.
    fixtureTransactionTemplate.executeWithoutResult(
        status -> {
          sync.syncTableAfterCommit(pub);
          insideBody.set(selectAs(1, pub));
        });
    assertThat(insideBody.get()).as("커밋 전에는 동기화하지 않는다").isEqualTo("42501");
    awaitPythonReadGrantSync(); // 커밋 뒤 동기화는 전용 실행기에서 돈다(WD-594)
    assertThat(selectAs(1, pub)).as("커밋 후 동기화됐다").isNull();
  }

  /** 트랜잭션 없음 → 즉시 동기화한다. */
  @Test
  void syncTableAfterCommit_withoutTransaction_runsImmediately() {
    String pub = table("ntx", "공개");
    revokeAllSlots(pub);
    assertThat(selectAs(1, pub)).isEqualTo("42501");
    sync.syncTableAfterCommit(pub);
    assertThat(selectAs(1, pub)).isNull();
  }

  /**
   * 테넌트 컨텍스트가 없어도 예외를 던지지 않고(로그만) 호출자 트랜잭션의 커밋을 막지 않는다 — 예약 단계(require)도 try 안이라는 계약. require 를 try
   * 밖으로 되돌리면 실패한다(변이).
   */
  @Test
  void syncTableAfterCommit_withoutTenantContext_doesNotThrowOrBlockCommit() {
    String pub = table("nctx", "공개");
    revokeAllSlots(pub);
    TenantContext.clear();
    try {
      assertThatCode(() -> sync.syncTableAfterCommit(pub)).doesNotThrowAnyException();
      assertThatCode(
              () ->
                  fixtureTransactionTemplate.executeWithoutResult(
                      status -> sync.syncTableAfterCommit(pub)))
          .as("트랜잭션 안에서도 커밋이 막히지 않는다")
          .doesNotThrowAnyException();
    } finally {
      TenantContext.set(DEFAULT_TEST_TENANT_ID);
    }
    awaitPythonReadGrantSync();
    assertThat(selectAs(1, pub)).as("컨텍스트 없으면 동기화는 건너뛴다(다음 동기화가 회복)").isEqualTo("42501");
  }

  /**
   * 회귀(WD-594): 풀 크기만큼의 트랜잭션이 <b>동시에</b> 커넥션을 쥔 채 syncTableAfterCommit 을 예약하고 커밋해도, 커밋이 두 번째 커넥션을
   * 기다려 멈추지 않고(커넥션 타임아웃 30초) 모든 테이블이 GRANT 된다. 고치기 전에는 afterCommit 에서 호출 스레드가 바깥 커넥션을 쥔 채
   * REQUIRES_NEW 로 커넥션을 하나 더 기다려, 풀(테스트 2)을 다 쥔 요청들이 서로 막혀 30초 뒤 동기화가 실패했다 — 시간 단언과 GRANT 단언이 모두
   * 깨진다.
   */
  @Test
  void syncTableAfterCommit_concurrentCommitsAtPoolSize_doNotHoldTwoConnections() throws Exception {
    int poolSize = dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize();
    assertThat(poolSize).as("전제: 테스트 풀이 작아야 동시 커밋으로 풀을 다 쥘 수 있다").isBetween(1, 4);
    List<String> tables = new ArrayList<>();
    for (int i = 0; i < poolSize; i++) {
      String t = table("cc" + i, "공개");
      revokeAllSlots(t);
      tables.add(t);
    }
    // 모든 트랜잭션이 커넥션을 쥔 상태에서 함께 커밋에 들어가게 맞춘다(= 풀 소진 상태에서 커밋 콜백).
    List<Long> elapsed =
        commitConcurrentlyAtPoolSize(poolSize, i -> () -> sync.syncTableAfterCommit(tables.get(i)));
    for (Long ms : elapsed) {
      assertThat(ms).as("커밋이 두 번째 커넥션을 기다려 멈추지 않는다(커넥션 타임아웃 30초)").isLessThan(10_000L);
    }
    awaitPythonReadGrantSync();
    for (String t : tables) {
      assertThat(selectAs(1, t)).as("커밋 뒤 동기화가 실패하지 않고 GRANT 했다: %s", t).isNull();
    }
  }

  /** 테이블 DDL 한 문장을 자기 트랜잭션에서 돌리고, 실패하면 그 사유를 failures 에 남긴다(사용자 요청이면 500 이 되는 오류). */
  private void ddl(String sql, List<String> failures) {
    try {
      TenantRlsTestSupport.runInTenantTransaction(
          fixtureTransactionTemplate, DEFAULT_TEST_TENANT_ID, () -> dataDsl.execute(sql));
    } catch (RuntimeException e) {
      failures.add(sql + " → " + e.getMessage());
    }
  }

  /** 커밋 뒤 실행기에 넘어간 동기화가 아직 돌고 있는가(기다리지 않고 본다). */
  private boolean syncPending() throws InterruptedException {
    return !syncExecutor.awaitIdle(Duration.ZERO);
  }

  /**
   * 회귀(WD-594 후속): 커밋 뒤 동기화가 전용 실행기로 넘어가면서, 데이터셋 생성 직후의 같은 테이블 DDL(ALTER TABLE ADD COLUMN·DROP
   * TABLE)과 동기화의 GRANT/REVOKE 가 겹칠 수 있게 됐다. GRANT/REVOKE 는 테이블 잠금 없이 pg_class 행(ACL)을 고치므로, 고치기 전에는
   * 겹친 DDL 이 {@code tuple concurrently updated}(XX000)로 깨졌다(사용자에게는 500). 동기화가 바꿀 테이블에 ACCESS SHARE
   * 잠금을 먼저 잡으면 DDL 은 동기화 커밋을 기다렸다 성공하고, 동기화는 진행 중인 DDL 뒤의 새 카탈로그를 본다 — DDL 오류가 없고 GRANT 도 계산값대로
   * 남는다.
   */
  @Test
  void syncTableAfterCommit_overlappingAlterAndDrop_doNotFailWithTupleConcurrentlyUpdated()
      throws Exception {
    List<String> failures = new ArrayList<>();
    String slot1 = TenantPipelineRole.pythonReadRoleName(DEFAULT_TEST_TENANT_ID, 1);

    // (1) ALTER TABLE ADD COLUMN — 매 회 슬롯 GRANT 를 걷어 동기화가 GRANT 하게 만들고, 커밋 직후부터 동기화가 끝날 때까지 ADD
    // COLUMN 을 연달아 던진다.
    String t = table("ddl", "공개");
    String qt = DataSchema.qualify(t);
    int col = 0;
    for (int round = 0; round < 25; round++) {
      revokeAllSlots(t);
      fixtureTransactionTemplate.executeWithoutResult(status -> sync.syncTableAfterCommit(t));
      // 동기화가 이미 끝났어도 최소 한 번은 던진다. 열 수 상한(1600)을 넘지 않게 회당 상한을 둔다.
      int perRound = 0;
      do {
        ddl("ALTER TABLE " + qt + " ADD COLUMN c" + (col++) + " TEXT", failures);
      } while (syncPending() && ++perRound < 30);
      awaitPythonReadGrantSync();
      if (selectAs(1, t) != null) {
        failures.add("round " + round + ": 겹친 동기화 뒤 슬롯 1 GRANT 가 없다");
      }
    }

    // (2) DROP TABLE — 데이터셋이 아닌 테이블에 슬롯 GRANT 를 걸어 두면(과권한) 동기화가 REVOKE 한다. 그 REVOKE 와 커밋 직후의 DROP 이
    // 겹친다.
    for (int round = 0; round < 25; round++) {
      String raw = m + "_drop" + round;
      String qraw = DataSchema.qualify(raw);
      ddl("CREATE TABLE " + qraw + " (id int)", failures);
      ddl("GRANT SELECT ON TABLE " + qraw + " TO \"" + slot1 + "\"", failures);
      fixtureTransactionTemplate.executeWithoutResult(status -> sync.syncTableAfterCommit(raw));
      ddl("DROP TABLE " + qraw, failures);
      awaitPythonReadGrantSync();
    }

    assertThat(failures).as("동기화와 겹친 DDL 이 실패하지 않고, GRANT 는 계산값대로 남는다").isEmpty();
  }

  // ---------------------------------------------------------------- 이벤트 리스너

  /**
   * 등급 정의 변경 이벤트 → 커밋 후 테넌트 전체 재동기화. 롤백되면 돌지 않고(AFTER_COMMIT), 발행 스레드 테넌트가 엉뚱해도 event.tenantId() 로
   * 동기화한다. 리스너 본문(또는 runScoped)을 지우면 실패한다(변이).
   */
  @Test
  void levelsChangedEvent_resyncsTenantAfterCommit() {
    String sens = table("sen", "민감"); // ACL 은 생성 시점(내부) 기준 → 슬롯 2 에 남는 권한
    assertThat(selectAs(2, sens)).as("출발 상태: 과권한").isNull();
    SecurityLevelsChangedEvent event =
        new SecurityLevelsChangedEvent(
            DEFAULT_TEST_TENANT_ID, SecurityLevelsChangedEvent.Kind.REORDERED, null);

    publishInTx(event, false);
    assertThat(selectAs(2, sens)).as("롤백된 발행은 리스너가 돌지 않는다").isNull();

    publishInTx(event, true);
    assertThat(selectAs(2, sens)).isEqualTo("42501");
    assertThat(selectAs(3, sens)).isNull();
  }

  /**
   * 실제 순서 변경 → 커밋되는 같은 트랜잭션에서 REORDERED 발행 → 슬롯 판정이 새 위치를 따라 뒤집힌다. 맨 아래 두 등급(공개·내부)의 rank 를 맞바꾸면 공개
   * 테이블은 위치 2 가 되어 슬롯 1 이 못 읽고, 내부 테이블은 위치 1 이 되어 슬롯 1 이 읽는다. 리스너 단독 검증이라 테스트가 직접 발행한다 — B 병합 후
   * applyReorder 도 발행하므로 이 테스트에선 2번 발행된다(재동기화는 멱등). 서비스 발행만의 종단은
   * applyReorder_viaService_flipsSlotAccessAfterCommit. 리스너 본문을 지우면 ACL 이 옛 순서에 남아 실패한다(변이).
   */
  @Test
  void reorderLevels_withReorderedEvent_flipsSlotAccess() {
    String pub = table("ro_pub", "공개");
    String internal = table("ro_int", "내부");
    sync.syncTenant();
    assertThat(selectAs(1, pub)).as("출발: 공개(위치 1)는 슬롯 1 이 읽는다").isNull();
    assertThat(selectAs(1, internal)).as("출발: 내부(위치 2)는 슬롯 1 이 못 읽는다").isEqualTo("42501");

    List<Long> original =
        TenantRlsTestSupport.runInTenantTransaction(
            fixtureTransactionTemplate,
            DEFAULT_TEST_TENANT_ID,
            () -> levelRepository.findAll().stream().map(LevelPolicy::id).toList());
    assertThat(original.subList(0, 2))
        .as("전제: 맨 아래 두 등급이 공개·내부")
        .containsExactly(fx.levelId("공개"), fx.levelId("내부"));
    List<Long> swapped = new ArrayList<>(original);
    swapped.set(0, original.get(1));
    swapped.set(1, original.get(0));
    SecurityLevelsChangedEvent event =
        new SecurityLevelsChangedEvent(
            DEFAULT_TEST_TENANT_ID, SecurityLevelsChangedEvent.Kind.REORDERED, null);
    try {
      reorderAndPublish(swapped, event);
      assertThat(selectAs(1, pub)).as("공개가 위치 2 로 → 슬롯 1 거부").isEqualTo("42501");
      assertThat(selectAs(2, pub)).isNull();
      assertThat(selectAs(1, internal)).as("내부가 위치 1 로 → 슬롯 1 허용").isNull();
    } finally {
      // 같은 JVM 의 다른 테스트가 V133 시드 순서를 전제하므로 반드시 되돌리고 ACL 도 맞춘다.
      reorderAndPublish(original, event);
    }
    assertThat(selectAs(1, pub)).as("원복 후 공개는 다시 슬롯 1 이 읽는다").isNull();
  }

  /** 슬롯 롤로 로그인한 연결 — 실행 중인 PYTHON 스크립트의 DB 세션을 흉내 낸다. */
  private Connection openSlotSession(int slot) throws SQLException {
    return DriverManager.getConnection(
        PostgresTestContainer.dataStoreJdbcUrl(),
        TenantPipelineRole.pythonReadRoleName(DEFAULT_TEST_TENANT_ID, slot),
        TenantPipelineRole.pythonReadPassword(DEFAULT_TEST_TENANT_ID, slot, secret));
  }

  /** 연결로 쿼리 하나를 돌린다 — 성공하면 null, 실패하면 SQLState(끊긴 연결이면 57P01·08xxx). */
  private static String queryState(Connection c) {
    try (Statement st = c.createStatement()) {
      st.execute("SELECT 1");
      return null;
    } catch (SQLException e) {
      return e.getSQLState() == null ? "closed" : e.getSQLState();
    }
  }

  /**
   * CR2 권한 실측(이 테스트 DB): 런타임 롤 app_tenant 는 pg_signal_backend 멤버도 슬롯 롤 멤버도 아니라 슬롯 롤 세션을 끊을 수 없고,
   * 소유자 롤(app, schemaOwnerDataSource)은 슈퍼유저라 끊을 수 있다. 끊기를 소유자 연결로 하는 근거이며, 이 전제가 바뀌면 이 테스트가 먼저 알린다.
   */
  @Test
  void terminatePrivilege_runtimeRoleCannot_ownerRoleCan() {
    String s1 = TenantPipelineRole.pythonReadRoleName(DEFAULT_TEST_TENANT_ID, 1);
    assertThat(
            dsl.fetchValue(
                "select pg_has_role(current_user, 'pg_signal_backend', 'USAGE') or pg_has_role(current_user, {0}, 'USAGE')"
                    + " or (select rolsuper from pg_roles where rolname = current_user)",
                DSL.val(s1)))
        .as("런타임 롤(app_tenant)은 슬롯 롤 세션을 끊을 권한이 없다")
        .isEqualTo(false);
    assertThat(
            DSL.using(schemaOwnerDataSource, org.jooq.SQLDialect.POSTGRES)
                .fetchValue("select rolsuper from pg_roles where rolname = current_user"))
        .as("소유자 롤(app)은 슈퍼유저")
        .isEqualTo(true);
  }

  /**
   * CR2 — 등급 정의 변경 이벤트는 새 GRANT 커밋 전에 그 테넌트 슬롯 롤의 활성 세션을 끊는다(실행 중 스크립트가 넓어진 슬롯으로 자격 밖 등급을 읽지 못하게).
   * 변이: 리스너 경로의 세션 종료 호출을 지우면 연결이 살아 남아 실패한다.
   */
  @Test
  void levelsChangedEvent_terminatesRunningSlotSessions() throws SQLException {
    try (Connection running = openSlotSession(2)) {
      assertThat(queryState(running)).as("대조군: 이벤트 전에는 살아 있다").isNull();
      publishInTx(
          new SecurityLevelsChangedEvent(
              DEFAULT_TEST_TENANT_ID, SecurityLevelsChangedEvent.Kind.REORDERED, null),
          true);
      assertThat(queryState(running)).as("등급 정의 변경 후 슬롯 롤 세션은 끊긴다").isNotNull();
    }
    assertThat(sync.slotSessionsTerminatedSince(DEFAULT_TEST_TENANT_ID, 0L))
        .as("러너 실패 문구용 표식")
        .isTrue();
  }

  /**
   * 데이터셋 하나의 등급 변경은 세션을 끊지 않는다 — 슬롯↔등급 위치 대응이 그대로라 늘어나는 GRANT 는 이미 자격 있는 슬롯에만 가고, 줄어드는 GRANT 는 다음
   * 쿼리부터 막힌다(PostgreSQL 은 매 쿼리 권한을 본다). 끊긴 연결이 아닌 권한 오류(42501)로 막히는 것을 함께 단언한다.
   */
  @Test
  void datasetLevelChangedEvent_doesNotTerminate_butRevokesFromNextQuery() throws SQLException {
    String t = table("dl_live", "공개");
    long id = lastDatasetId();
    sync.syncTenant();
    try (Connection running = openSlotSession(1);
        Statement st = running.createStatement()) {
      String sql =
          "SELECT v FROM " + DataSchema.forTenant(DEFAULT_TEST_TENANT_ID) + ".\"" + t + "\"";
      st.executeQuery(sql).close();
      TenantRlsTestSupport.runInTenantTransaction(
          fixtureTransactionTemplate,
          DEFAULT_TEST_TENANT_ID,
          () ->
              dsl.update(DATASET)
                  .set(DATASET.SECURITY_LEVEL_ID, fx.levelId("민감"))
                  .where(DATASET.ID.eq(id))
                  .execute());
      publishInTx(
          new DatasetSecurityLevelChangedEvent(
              DEFAULT_TEST_TENANT_ID,
              id,
              fx.levelId("공개"),
              fx.levelId("민감"),
              DatasetSecurityLevelChangedEvent.Cause.MANUAL),
          true);
      assertThatCode(() -> st.executeQuery("SELECT 1").close())
          .as("세션은 살아 있다")
          .doesNotThrowAnyException();
      String deniedState = null;
      try {
        st.executeQuery(sql).close();
      } catch (SQLException e) {
        deniedState = e.getSQLState();
      }
      assertThat(deniedState).as("같은 세션의 다음 쿼리부터 권한 오류로 거부").isEqualTo("42501");
    }
  }

  /** 테넌트 트랜잭션 안에서 순서를 적용하고 같은 트랜잭션에서 이벤트를 발행한다(커밋 후 리스너 실행). */
  private void reorderAndPublish(List<Long> orderedIds, SecurityLevelsChangedEvent event) {
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> {
          levelService.applyReorder(orderedIds, owner);
          events.publishEvent(event);
        });
    awaitPythonReadGrantSync(); // 리스너 동기화는 커밋 뒤 실행기에서 돈다(WD-624)
  }

  // ------------------------------------- 등급 이벤트 리스너 — 커넥션 1개·보안 순서(WD-624)

  /** 커밋되는 트랜잭션 안에서 이벤트를 발행하고 실행기를 <b>비우지 않고</b> 돌아온다(커밋 직후 상태를 보는 순서 테스트용). */
  private void publishCommittedNoDrain(Object event) {
    TenantContext.runScoped(
        ambientFor(event),
        () ->
            fixtureTransactionTemplate.executeWithoutResult(status -> events.publishEvent(event)));
  }

  /**
   * 풀 크기만큼의 트랜잭션이 커넥션을 쥔 채 함께 커밋에 들어가게 맞추고(각자 트랜잭션 안에서 {@code inTx(i)} 를 실행 — 예약·이벤트 발행), 각 커밋이
   * 끝나기까지 걸린 시간(ms)을 돌려준다. WD-594(테이블 훅)·WD-624(등급 이벤트) 회귀 테스트가 함께 쓰는 장치다.
   */
  private List<Long> commitConcurrentlyAtPoolSize(
      int poolSize, java.util.function.IntFunction<Runnable> inTx) throws Exception {
    CyclicBarrier allHolding = new CyclicBarrier(poolSize);
    ExecutorService pool = Executors.newFixedThreadPool(poolSize);
    try {
      List<Future<Long>> commits = new ArrayList<>();
      for (int i = 0; i < poolSize; i++) {
        Runnable action = inTx.apply(i);
        commits.add(
            pool.submit(
                () ->
                    TenantContext.runScopedGet(
                        DEFAULT_TEST_TENANT_ID,
                        () -> {
                          long start = System.nanoTime();
                          fixtureTransactionTemplate.executeWithoutResult(
                              status -> {
                                action.run();
                                try {
                                  allHolding.await(10, TimeUnit.SECONDS);
                                } catch (Exception e) {
                                  throw new IllegalStateException("동시 커밋 맞추기 실패", e);
                                }
                              });
                          return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                        })));
      }
      List<Long> elapsed = new ArrayList<>();
      for (Future<Long> commit : commits) {
        elapsed.add(commit.get(60, TimeUnit.SECONDS));
      }
      return elapsed;
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * 회귀(WD-624): 풀 크기만큼의 트랜잭션이 <b>동시에</b> 데이터셋 등급 변경 이벤트를 발행·커밋해도 커밋이 두 번째 커넥션을 기다려 멈추지 않고(커넥션 타임아웃
   * 30초), 실행기가 비면 각 테이블이 새 등급(민감, 위치 3)대로 맞춰진다. 고치기 전에는 리스너가 커밋 콜백에서 호출 스레드로 REQUIRES_NEW 동기화를 돌려
   * 바깥 커넥션을 쥔 채 하나를 더 기다렸다 — 풀(테스트 2)을 다 쥔 요청들이 서로 막혀 30초 뒤 동기화가 실패해 시간·GRANT 단언이 모두 깨진다.
   */
  @Test
  void datasetLevelChangedEvents_concurrentCommitsAtPoolSize_doNotHoldTwoConnections()
      throws Exception {
    int poolSize = dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize();
    assertThat(poolSize).as("전제: 테스트 풀이 작아야 동시 커밋으로 풀을 다 쥘 수 있다").isBetween(1, 4);
    List<String> tables = new ArrayList<>();
    List<Long> ids = new ArrayList<>();
    for (int i = 0; i < poolSize; i++) {
      tables.add(table("dlc" + i, "민감")); // ACL 은 생성 시점(내부) 기준 → 슬롯 2 에 남는 권한
      ids.add(lastDatasetId());
    }
    assertThat(selectAs(2, tables.get(0))).as("출발 상태: 과권한").isNull();

    List<Long> elapsed =
        commitConcurrentlyAtPoolSize(
            poolSize,
            i ->
                () ->
                    events.publishEvent(
                        new DatasetSecurityLevelChangedEvent(
                            DEFAULT_TEST_TENANT_ID,
                            ids.get(i),
                            fx.levelId("내부"),
                            fx.levelId("민감"),
                            DatasetSecurityLevelChangedEvent.Cause.MANUAL)));
    assertThat(elapsed).as("커밋이 두 번째 커넥션을 기다려 멈추지 않는다(커넥션 타임아웃 30초)").allMatch(ms -> ms < 10_000L);

    awaitPythonReadGrantSync();
    for (String t : tables) {
      assertThat(selectAs(2, t)).as("커밋 뒤 동기화가 남는 권한을 회수했다: %s", t).isEqualTo("42501");
      assertThat(selectAs(3, t)).as("슬롯 3 은 읽는다: %s", t).isNull();
    }
  }

  /**
   * 회귀(WD-624): 풀 크기만큼의 트랜잭션이 <b>동시에</b> 등급 정의 변경 이벤트를 발행·커밋해도 멈추지 않고, 실행기가 비면 테넌트 전체가 계산값대로
   * 맞춰진다(세션 끊기는 커밋 콜백에서 동기로 돌지만 소유자 풀만 쓰므로 플랫폼 풀을 기다리지 않는다). 고치기 전에는 30초 멈춘 뒤 동기화가 실패했다.
   */
  @Test
  void levelsChangedEvents_concurrentCommitsAtPoolSize_doNotHoldTwoConnections() throws Exception {
    int poolSize = dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize();
    assertThat(poolSize).as("전제: 테스트 풀이 작아야 동시 커밋으로 풀을 다 쥘 수 있다").isBetween(1, 4);
    String sens = table("lvc", "민감"); // ACL 은 생성 시점(내부) 기준 → 슬롯 2 에 남는 권한
    assertThat(selectAs(2, sens)).as("출발 상태: 과권한").isNull();

    List<Long> elapsed =
        commitConcurrentlyAtPoolSize(
            poolSize,
            i ->
                () ->
                    events.publishEvent(
                        new SecurityLevelsChangedEvent(
                            DEFAULT_TEST_TENANT_ID,
                            SecurityLevelsChangedEvent.Kind.REORDERED,
                            null)));
    assertThat(elapsed).as("커밋이 두 번째 커넥션을 기다려 멈추지 않는다(커넥션 타임아웃 30초)").allMatch(ms -> ms < 10_000L);

    awaitPythonReadGrantSync();
    assertThat(selectAs(2, sens)).as("커밋 뒤 전체 동기화가 남는 권한을 회수했다").isEqualTo("42501");
    assertThat(selectAs(3, sens)).isNull();
  }

  /**
   * 합치기(WD-624): 실행기가 막혀 전체 동기화가 아직 시작하지 않은 동안 같은 테넌트의 등급 정의 변경이 연달아 커밋돼도, 세션 끊기는 변경마다 커밋 직후 돌고,
   * 실행기가 비면 (합쳐진) 전체 동기화가 마지막 상태까지 맞춘다. 큐에 하나만 들어가는지(호출 횟수)는 실행기를 그대로 쓰는 단위 테스트
   * PythonReadGrantListenerTest 가 센다 — 운영 API 에 테스트 전용 카운터를 두지 않는다.
   */
  @Test
  void levelsChangedEvents_whileSyncPending_coalesceIntoOneFullSync() throws Exception {
    String sens = table("coal", "민감"); // ACL 은 내부 기준 → 슬롯 2 에 남는 권한
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    syncExecutor.submit(
        () -> {
          try {
            release.await(30, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    try {
      for (int i = 0; i < 3; i++) {
        try (Connection running = openSlotSession(2)) {
          publishCommittedNoDrain(
              new SecurityLevelsChangedEvent(
                  DEFAULT_TEST_TENANT_ID, SecurityLevelsChangedEvent.Kind.REORDERED, null));
          assertThat(queryState(running)).as("변경 %d: 커밋 직후 세션이 끊겼다", i).isNotNull();
        }
      }
    } finally {
      release.countDown();
    }
    awaitPythonReadGrantSync();
    assertThat(selectAs(2, sens)).as("합쳐진 한 번의 전체 동기화가 맞췄다").isEqualTo("42501");
    assertThat(selectAs(3, sens)).isNull();

    // 시작할 때 표식을 지웠으므로 다음 변경은 다시 작업을 넣는다(합치기가 영구히 막지 않는다).
    revokeAllSlots(sens);
    publishInTx(
        new SecurityLevelsChangedEvent(
            DEFAULT_TEST_TENANT_ID, SecurityLevelsChangedEvent.Kind.REORDERED, null),
        true);
    assertThat(selectAs(3, sens)).as("다음 변경의 전체 동기화도 돈다").isNull();
  }

  /**
   * 보안 순서(WD-624): 등급 정의 변경이 커밋되어 돌아온 시점에는 — 넓어지는 GRANT 를 맞추는 전체 동기화가 아직 실행기 큐에서 기다리는 중이어도 — 옛 위치로
   * 슬롯을 받아 실행 중이던 슬롯 롤 세션이 <b>이미 끊겨</b> 있다. 그래서 큐 대기 중 다른 경로(실행 준비·테이블 동기화)가 슬롯을 먼저 넓혀도 그 넓어짐을 볼 옛
   * 세션이 없다. 실행기를 막아 "동기화 전" 창을 고정한 뒤 단언한다. 변이: 세션 끊기를 실행기 작업 안으로만 옮기면(커밋 콜백의 동기 끊기를 지우면) 첫 단언이
   * 실패한다.
   */
  @Test
  void levelsChangedEvent_terminatesSessionsBeforeDeferredSync() throws Exception {
    String sens = table("ord", "민감"); // ACL 은 내부 기준 → 슬롯 2 에 남는 권한(전체 동기화가 회수할 대상)
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    syncExecutor.submit(
        () -> {
          try {
            release.await(30, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    try (Connection running = openSlotSession(2)) {
      assertThat(queryState(running)).as("대조군: 이벤트 전에는 살아 있다").isNull();
      publishCommittedNoDrain(
          new SecurityLevelsChangedEvent(
              DEFAULT_TEST_TENANT_ID, SecurityLevelsChangedEvent.Kind.REORDERED, null));
      assertThat(syncPending()).as("전제: 전체 동기화는 아직 실행기 큐에 있다").isTrue();
      assertThat(queryState(running)).as("커밋 직후 — 동기화 전에 이미 끊겼다").isNotNull();
      assertThat(selectAs(2, sens)).as("전제: ACL 은 아직 옛 상태(전체 동기화 전)").isNull();
    } finally {
      release.countDown();
    }
    awaitPythonReadGrantSync();
    assertThat(selectAs(2, sens)).as("실행기가 비면 전체 동기화가 맞춘다").isEqualTo("42501");
  }

  /** 실행기를 막는 작업을 넣고, 풀어 줄 래치를 돌려준다(커밋 뒤 비동기 단계가 "아직 안 돈" 창을 고정한다). */
  private java.util.concurrent.CountDownLatch blockSyncExecutor() {
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    syncExecutor.submit(
        () -> {
          try {
            release.await(30, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    return release;
  }

  /**
   * 반려 2(WD-624): 데이터셋 등급을 올린(좁힌) 변경이 커밋되어 돌아온 시점에는 — 그 테이블 동기화가 아직 실행기 큐에서 기다리는 중이어도 — <b>이미 실행
   * 중이던</b> 슬롯 세션의 다음 쿼리가 권한 오류로 막힌다. 좁히기(REVOKE)가 등급 변경과 같은 트랜잭션에서 커밋 직전에 돌기 때문이다. 실행기를 막아 "동기화 전"
   * 창을 고정한 뒤 단언한다. 고치기 전(REVOKE 를 실행기 작업에만 두던 판)에는 큐가 빌 때까지 세션이 계속 읽어 첫 단언이 실패했다. 커밋 뒤 시작하는 새 실행도 못
   * 읽는다(실행 준비가 커밋된 등급으로 자기 슬롯을 맞춘다).
   */
  @Test
  void datasetLevelRaised_runningSessionDeniedBeforeCommitReturns_evenWithSyncQueued()
      throws Exception {
    String t = table("narrow", "공개");
    long id = lastDatasetId();
    sync.syncTenant();
    String sql = "SELECT v FROM " + DataSchema.forTenant(DEFAULT_TEST_TENANT_ID) + ".\"" + t + "\"";
    int internalRank =
        TenantRlsTestSupport.runInTenantTransaction(
            fixtureTransactionTemplate,
            DEFAULT_TEST_TENANT_ID,
            () -> levelRepository.findById(fx.levelId("내부")).orElseThrow().rank());

    java.util.concurrent.CountDownLatch release = blockSyncExecutor();
    try (Connection running = openSlotSession(1);
        Statement st = running.createStatement()) {
      st.executeQuery(sql).close(); // 대조군: 변경 전에는 읽는다
      // 공개 → 민감 상향과 이벤트 발행을 한 트랜잭션에서 커밋한다(서비스와 같은 모양).
      TenantRlsTestSupport.runInTenantTransaction(
          fixtureTransactionTemplate,
          DEFAULT_TEST_TENANT_ID,
          () -> {
            dsl.update(DATASET)
                .set(DATASET.SECURITY_LEVEL_ID, fx.levelId("민감"))
                .where(DATASET.ID.eq(id))
                .execute();
            events.publishEvent(
                new DatasetSecurityLevelChangedEvent(
                    DEFAULT_TEST_TENANT_ID,
                    id,
                    fx.levelId("공개"),
                    fx.levelId("민감"),
                    DatasetSecurityLevelChangedEvent.Cause.MANUAL));
          });
      assertThat(syncPending()).as("전제: 테이블 동기화는 아직 실행기 큐에 있다").isTrue();
      String deniedState = null;
      try {
        st.executeQuery(sql).close();
      } catch (SQLException e) {
        deniedState = e.getSQLState();
      }
      assertThat(deniedState).as("커밋 응답 직후 — 실행 중 세션의 다음 쿼리가 이미 거부된다").isEqualTo("42501");
      assertThat(selectAs(2, t)).as("슬롯 2 도 이미 회수").isEqualTo("42501");
      assertThat(selectAs(3, t)).as("자격 있는 슬롯 3 은 그대로 읽는다(좁히기는 넓힌 것을 건드리지 않는다)").isNull();

      Clearance runAs =
          new Clearance(owner, DEFAULT_TEST_TENANT_ID, internalRank, Set.of(), false, Set.of());
      int slot = sync.prepareForRun(runAs);
      assertThat(slot).isEqualTo(2);
      assertThat(selectAs(slot, t)).as("새 실행도 동기화 전에 상향된 테이블을 못 읽는다").isEqualTo("42501");
    } finally {
      release.countDown();
    }
    awaitPythonReadGrantSync();
    assertThat(selectAs(2, t)).isEqualTo("42501");
    assertThat(selectAs(3, t)).isNull();
  }

  /** 데이터셋 등급을 내린(넓힌) 변경은 커밋 직전에 아무것도 넓히지 않는다 — 넓히기는 커밋 뒤 실행기 몫이다(좁히기만 동기, WD-624). 실행기가 비면 넓어진다. */
  @Test
  void datasetLevelLowered_widensOnlyAfterDeferredSync() throws Exception {
    String t = table("widen", "민감");
    long id = lastDatasetId();
    sync.syncTenant();
    assertThat(selectAs(1, t)).as("출발: 민감은 슬롯 1 이 못 읽는다").isEqualTo("42501");
    java.util.concurrent.CountDownLatch release = blockSyncExecutor();
    try {
      TenantRlsTestSupport.runInTenantTransaction(
          fixtureTransactionTemplate,
          DEFAULT_TEST_TENANT_ID,
          () -> {
            dsl.update(DATASET)
                .set(DATASET.SECURITY_LEVEL_ID, fx.levelId("공개"))
                .where(DATASET.ID.eq(id))
                .execute();
            events.publishEvent(
                new DatasetSecurityLevelChangedEvent(
                    DEFAULT_TEST_TENANT_ID,
                    id,
                    fx.levelId("민감"),
                    fx.levelId("공개"),
                    DatasetSecurityLevelChangedEvent.Cause.MANUAL));
          });
      assertThat(selectAs(1, t)).as("커밋 직전 단계는 넓히지 않는다").isEqualTo("42501");
    } finally {
      release.countDown();
    }
    awaitPythonReadGrantSync();
    assertThat(selectAs(1, t)).as("실행기가 넓힌다").isNull();
  }

  /**
   * 리뷰 4+5(WD-624): 데이터셋 등급 변경 이벤트의 테넌트가 발행 트랜잭션의 테넌트와 다르면 커밋 직전 좁히기를 하지 않는다(남의 RLS 범위를 읽어 조용히 아무것도
   * 못 회수하므로). 그래도 <b>발행 트랜잭션은 롤백하지 않는다</b> — 같은 트랜잭션의 다른 변경은 커밋되고, 대신 이벤트 테넌트의 모든 슬롯을 막는다(커밋 직후 실행
   * 중 세션 끊기 + 실행기 뒷정리). 고치기 전(예외로 롤백시키던 판)에는 발행 트랜잭션이 깨져 첫 단언이 실패했다.
   */
  @Test
  void datasetLevelChanged_tenantMismatch_commitsPublisher_andBlocksAllSlots() throws Exception {
    String t = table("mism", "공개");
    long id = lastDatasetId();
    sync.syncTenant();
    String marker = "prw_mismatch_" + System.nanoTime();
    long fromLevel = fx.levelId("공개"); // 등급 조회는 엉뚱한 테넌트 트랜잭션 밖에서(RLS)
    long toLevel = fx.levelId("민감");
    try (Connection running = openSlotSession(5)) {
      assertThat(queryState(running)).as("대조군").isNull();
      assertThatCode(
              () ->
                  TenantContext.runScoped(
                      WRONG_AMBIENT_TENANT,
                      () ->
                          fixtureTransactionTemplate.executeWithoutResult(
                              status -> {
                                // 이 트랜잭션의 다른 변경이 커밋되는지 본다(전역 테이블이라 RLS 와 무관하게 써진다).
                                dsl.execute(
                                    "insert into tenant (slug, name, status) values ({0}, {0}, 'ACTIVE')",
                                    DSL.val(marker));
                                events.publishEvent(
                                    new DatasetSecurityLevelChangedEvent(
                                        DEFAULT_TEST_TENANT_ID,
                                        id,
                                        fromLevel,
                                        toLevel,
                                        DatasetSecurityLevelChangedEvent.Cause.MANUAL));
                              })))
          .doesNotThrowAnyException();
      assertThat(dsl.fetchCount(DSL.table("tenant"), DSL.field("slug").eq(marker)))
          .as("발행 트랜잭션은 커밋됐다(구독자가 롤백시키지 않는다)")
          .isEqualTo(1);
      assertThat(queryState(running)).as("대신 이벤트 테넌트의 모든 슬롯 세션을 끊었다").isNotNull();
    } finally {
      dsl.execute("delete from tenant where slug = {0}", DSL.val(marker));
    }
    awaitPythonReadGrantSync();
    assertThat(selectAs(1, t)).as("뒷정리는 이벤트 테넌트로 돌아 실제 등급(공개)대로 맞춘다").isNull();
  }

  /**
   * 리뷰 4+5(WD-624): 커밋 직전 좁히기 안에서 PG 문장이 실패해도 발행 트랜잭션은 커밋되고, 같은 트랜잭션의 대체 경로(토큰 번호 올리기)도 반영된다. 좁히기는
   * savepoint 안에서 돌기 때문이다 — PG 는 문장 하나가 실패하면 트랜잭션 전체가 aborted 가 되어 뒤 문장과 커밋이 모두 실패한다. 두 경우를 본다:
   * 백엔드가 실패를 던짐 / 백엔드가 실패를 삼키고 "확인 못 함" 을 돌려줌(서브트랜잭션이 aborted 로 남아 RELEASE 가 실패하므로 savepoint 를 되돌려야
   * 한다). 고치기 전(savepoint 없음)에는 두 경우 모두, savepoint 만 있고 "확인 못 함" 에 되돌리지 않으면 두 번째 경우가 커밋에서 깨졌다.
   */
  @Test
  void narrowingStatementFailure_neverRollsBackPublisher_andFallbackStillApplies() {
    long tenant = TenantRlsTestSupport.createActiveTenant(dsl, "prw-sp");
    dsl.execute(
        "insert into tenant_data_store (tenant_id, backend, host, port, database_name, schema_name,"
            + " provision_mode, status) values ({0}, 'TIBERO', 'h', 8629, 'tibero', 'GENIA_T', 'DBA_AUTO', 'PENDING')",
        DSL.val(tenant));
    DataStoreResolver resolver = org.mockito.Mockito.mock(DataStoreResolver.class);
    org.mockito.Mockito.when(resolver.backendFor(org.mockito.ArgumentMatchers.anyLong()))
        .thenReturn(DataStoreBackend.POSTGRESQL);
    PythonReadGrantSync failingPg = org.mockito.Mockito.mock(PythonReadGrantSync.class);
    PythonReadGrants facade = new PythonReadGrants(resolver, failingPg, tiberoSync, txManager);
    List<String> markers = new ArrayList<>();
    try {
      for (boolean swallow : new boolean[] {false, true}) {
        // doAnswer 형태 — when(mock.call()) 은 앞 회차의 (던지는) 답을 실제로 불러 버린다.
        org.mockito.Mockito.doAnswer(
                inv -> {
                  if (!swallow) {
                    dsl.execute("select 1/0"); // 던진다
                  }
                  try {
                    dsl.execute("select 1/0");
                  } catch (RuntimeException ignored) {
                    // 삼키고 "확인 못 함" — 서브트랜잭션은 aborted 로 남는다
                  }
                  return PythonReadGrantBackend.NarrowResult.unconfirmed(Set.of(1, 2));
                })
            .when(failingPg)
            .narrowDatasetInCurrentTx(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong());
        String marker = "prw_sp_" + swallow + "_" + System.nanoTime();
        markers.add(marker);
        long before = epochOf(tenant);
        PythonReadGrantBackend.NarrowResult[] result = {null};
        boolean[] invalidated = {false};
        assertThatCode(
                () ->
                    TenantRlsTestSupport.runInTenantTransaction(
                        fixtureTransactionTemplate,
                        tenant,
                        () -> {
                          dsl.execute(
                              "insert into tenant (slug, name, status) values ({0}, {0}, 'ACTIVE')",
                              DSL.val(marker));
                          result[0] = facade.narrowDatasetInCurrentTx(1L, 1L, 2L);
                          invalidated[0] = facade.invalidateScriptTokensInCurrentTx(tenant);
                        }))
            .as("swallow=%s: 발행 트랜잭션 커밋 성공", swallow)
            .doesNotThrowAnyException();
        assertThat(result[0].needsFallback()).isTrue();
        assertThat(invalidated[0]).as("swallow=%s: 같은 트랜잭션의 대체 경로 문장이 돈다", swallow).isTrue();
        assertThat(dsl.fetchCount(DSL.table("tenant"), DSL.field("slug").eq(marker)))
            .as("swallow=%s: 발행 트랜잭션의 변경이 커밋됐다", swallow)
            .isEqualTo(1);
        assertThat(epochOf(tenant)).as("swallow=%s: 토큰 번호도 함께 커밋됐다", swallow).isEqualTo(before + 1);
      }
    } finally {
      for (String marker : markers) {
        dsl.execute("delete from tenant where slug = {0}", DSL.val(marker));
      }
      dsl.execute("delete from tenant_data_store where tenant_id = {0}", DSL.val(tenant));
    }
  }

  /** 다른 트랜잭션에서 테넌트 동기화 잠금을 잡고 놓을 때까지 쥔다(커밋 직전 좁히기가 잠금을 못 얻는 상황). 돌려준 래치를 내리면 놓는다. */
  private java.util.concurrent.CountDownLatch holdTenantSyncLock(ExecutorService holder)
      throws Exception {
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1);
    holder.submit(
        () ->
            TenantRlsTestSupport.runInTenantTransaction(
                fixtureTransactionTemplate,
                DEFAULT_TEST_TENANT_ID,
                () -> {
                  dsl.fetchValue(
                      "select pg_advisory_xact_lock(hashtextextended({0}, 0))",
                      DSL.val(PythonReadGrantSync.syncLockKey(DEFAULT_TEST_TENANT_ID)));
                  held.countDown();
                  try {
                    release.await(30, TimeUnit.SECONDS);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                }));
    assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
    return release;
  }

  /** 지금 다른 트랜잭션이 테넌트 동기화 잠금을 쥐고 있지 않은가(기다리지 않고 시도). */
  private boolean tenantSyncLockFree() {
    return TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () ->
            Boolean.TRUE.equals(
                dsl.fetchValue(
                    DSL.field(
                        "pg_try_advisory_xact_lock(hashtextextended({0}, 0))",
                        Boolean.class,
                        DSL.val(PythonReadGrantSync.syncLockKey(DEFAULT_TEST_TENANT_ID))))));
  }

  /**
   * 리뷰 1·2(WD-624): 커밋 직전 좁히기가 잠금을 못 얻어 확인하지 못하면, 커밋 직후 <b>그 테이블을 더는 읽으면 안 되는 슬롯만</b> 끊는다(공개→민감 =
   * 슬롯 1·2 — 슬롯 3 세션은 살아 있다). 첫 끊기 뒤에 붙은 세션(커밋 직전에 옛 등급으로 준비된 실행이 늦게 접속한 경우)도 실행기 뒷정리가 다시 끊고 회수한다.
   * 고치기 전에는 대체 경로가 테넌트의 모든 슬롯을 끊었고(슬롯 3 단언 실패), 뒷정리가 다시 끊지 않아 늦게 붙은 세션이 계속 읽었다.
   */
  @Test
  void datasetNarrowingUnconfirmed_killsOnlyExposedSlots_andCleanupKillsLateSessions()
      throws Exception {
    String t = table("fb", "공개");
    long id = lastDatasetId();
    sync.syncTenant();
    long from = fx.levelId("공개");
    long to = fx.levelId("민감");
    ExecutorService holder = Executors.newSingleThreadExecutor();
    java.util.concurrent.CountDownLatch lockRelease = holdTenantSyncLock(holder);
    java.util.concurrent.CountDownLatch syncRelease = blockSyncExecutor();
    Connection late = null;
    try (Connection s1 = openSlotSession(1);
        Connection s3 = openSlotSession(3)) {
      TenantRlsTestSupport.runInTenantTransaction(
          fixtureTransactionTemplate,
          DEFAULT_TEST_TENANT_ID,
          () -> {
            dsl.update(DATASET)
                .set(DATASET.SECURITY_LEVEL_ID, to)
                .where(DATASET.ID.eq(id))
                .execute();
            events.publishEvent(
                new DatasetSecurityLevelChangedEvent(
                    DEFAULT_TEST_TENANT_ID,
                    id,
                    from,
                    to,
                    DatasetSecurityLevelChangedEvent.Cause.AUTO_RAISE));
          });
      assertThat(queryState(s1)).as("막을 슬롯 1 세션은 커밋 응답 전에 끊겼다").isNotNull();
      assertThat(queryState(s3)).as("자격이 그대로인 슬롯 3 세션은 끊지 않는다").isNull();

      late = openSlotSession(1);
      String sql =
          "SELECT v FROM " + DataSchema.forTenant(DEFAULT_TEST_TENANT_ID) + ".\"" + t + "\"";
      try (Statement st = late.createStatement()) {
        st.executeQuery(sql).close(); // 전제: 회수 전이라 늦게 붙은 세션은 아직 읽는다
      }
      lockRelease.countDown();
      syncRelease.countDown();
      awaitPythonReadGrantSync();
      assertThat(queryState(late)).as("뒷정리가 늦게 붙은 슬롯 1 세션도 끊었다").isNotNull();
      assertThat(queryState(s3)).as("슬롯 3 세션은 끝까지 살아 있다").isNull();
      assertThat(selectAs(1, t)).isEqualTo("42501");
      assertThat(selectAs(2, t)).isEqualTo("42501");
      assertThat(selectAs(3, t)).isNull();
    } finally {
      lockRelease.countDown();
      syncRelease.countDown();
      if (late != null) {
        late.close();
      }
      holder.shutdown();
      assertThat(holder.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  /**
   * 리뷰 3(WD-624): 소유자 풀이 막혀 끊기가 늦어져도, 끊기를 기다리는 실행 준비(미확인 표식이 있는 테넌트)와 등급 정의 변경 전체 동기화가 <b>테넌트 동기화
   * 잠금을 쥐지 않는다</b> — 그동안 다른 트랜잭션이 바로 잠금을 얻는다(다른 실행 준비·커밋 직전 좁히기가 함께 멈추지 않는다). 소유자 풀이 풀리면 실행 준비가
   * 끝난다. 고치기 전에는 실행 준비가 잠금 안에서 끊기를 기다려 잠금 확인이 실패했다.
   */
  @Test
  void ownerPoolBusy_prepareAndLevelsSync_waitForKillWithoutHoldingTenantLock() throws Exception {
    String t = table("own", "공개");
    sync.syncTenant();
    int publicRank =
        TenantRlsTestSupport.runInTenantTransaction(
            fixtureTransactionTemplate,
            DEFAULT_TEST_TENANT_ID,
            () -> levelRepository.findById(fx.levelId("공개")).orElseThrow().rank());
    Connection ownerHeld = schemaOwnerDataSource.getConnection(); // 소유자 풀(크기 1)을 막는다
    boolean ownerReleased = false;
    ExecutorService runner = Executors.newSingleThreadExecutor();
    try {
      publishCommittedNoDrain(
          new SecurityLevelsChangedEvent(
              DEFAULT_TEST_TENANT_ID, SecurityLevelsChangedEvent.Kind.REORDERED, null));
      Clearance runAs =
          new Clearance(owner, DEFAULT_TEST_TENANT_ID, publicRank, Set.of(), false, Set.of());
      Future<Integer> prepared =
          runner.submit(
              () ->
                  TenantContext.runScopedGet(
                      DEFAULT_TEST_TENANT_ID, () -> sync.prepareForRun(runAs)));
      Thread.sleep(500); // 실행 준비가 끊기 기다림에 들어갈 시간
      assertThat(prepared.isDone()).as("전제: 실행 준비가 끊기를 기다리는 중").isFalse();
      long start = System.nanoTime();
      assertThat(tenantSyncLockFree()).as("끊기를 기다리는 동안 테넌트 동기화 잠금은 비어 있다").isTrue();
      assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(2_000L);

      ownerHeld.close();
      ownerReleased = true;
      int slot = prepared.get(20, TimeUnit.SECONDS);
      assertThat(selectAs(slot, t)).as("소유자 풀이 풀리면 실행 준비가 끝나 읽는다").isNull();
      awaitPythonReadGrantSync();
    } finally {
      if (!ownerReleased) {
        ownerHeld.close();
      }
      runner.shutdownNow();
    }
  }

  /**
   * 반려 1(WD-624): 등급 정의 변경은 스크립트 토큰 세대 번호를 <b>발행 트랜잭션 안에서</b> 올린다 — 실행기가 막혀 전체 동기화가 아직 큐에 있어도 커밋 응답
   * 시점에 번호가 이미 올라 있어, 옛 토큰을 쥔 Tibero 실행 중 스크립트의 다음 읽기가 거부된다. 롤백된 변경은 번호를 올리지 않는다(같은 트랜잭션). 고치기 전(번호
   * 올리기를 실행기 작업에만 두던 판)에는 큐가 빌 때까지 번호가 그대로라 첫 단언이 실패했다. Tibero 저장소 행(PENDING — 슬롯 계정이 없어 실행기 동기화는
   * 외부 접속 없이 끝난다)을 가진 별도 테넌트로 검증한다.
   */
  @Test
  void levelsChanged_bumpsScriptTokenEpochInPublishingTransaction_evenWithSyncQueued()
      throws Exception {
    long tenant = TenantRlsTestSupport.createActiveTenant(dsl, "prw-epoch");
    dsl.execute(
        "insert into tenant_data_store (tenant_id, backend, host, port, database_name, schema_name,"
            + " provision_mode, status) values ({0}, 'TIBERO', 'h', 8629, 'tibero', 'GENIA_T', 'DBA_AUTO', 'PENDING')",
        DSL.val(tenant));
    try {
      assertThat(epochOf(tenant)).isZero();
      SecurityLevelsChangedEvent event =
          new SecurityLevelsChangedEvent(tenant, SecurityLevelsChangedEvent.Kind.REORDERED, null);
      java.util.concurrent.CountDownLatch release = blockSyncExecutor();
      try {
        TenantRlsTestSupport.runInTenantTransaction(
            fixtureTransactionTemplate, tenant, () -> events.publishEvent(event));
        assertThat(syncPending()).as("전제: 전체 동기화는 아직 실행기 큐에 있다").isTrue();
        assertThat(epochOf(tenant)).as("커밋 응답 시점에 번호가 이미 올라 있다").isEqualTo(1L);

        TenantContext.runScoped(
            tenant,
            () ->
                fixtureTransactionTemplate.executeWithoutResult(
                    status -> {
                      events.publishEvent(event);
                      status.setRollbackOnly();
                    }));
        assertThat(epochOf(tenant)).as("롤백된 변경은 번호를 올리지 않는다").isEqualTo(1L);
      } finally {
        release.countDown();
      }
      awaitPythonReadGrantSync();
      assertThat(epochOf(tenant)).as("실행기의 전체 동기화가 넓히기 전에 한 번 더 올린다").isEqualTo(2L);
    } finally {
      dsl.execute("delete from tenant_data_store where tenant_id = {0}", DSL.val(tenant));
    }
  }

  private long epochOf(long tenant) {
    return dsl.select(DSL.field("script_token_epoch", Long.class))
        .from(DSL.table("tenant_data_store"))
        .where(DSL.field("tenant_id").eq(tenant))
        .fetchSingle()
        .value1();
  }

  /**
   * 반려 3(WD-624): 소유자 풀(크기 1)이 다른 일로 막혀 있어도, 풀 크기만큼 동시에 커밋되는 등급 정의 변경이 커밋 콜백에서 30초(소유자 풀 연결 대기 상한)씩
   * 멈추지 않는다 — 세션 끊기는 소유자 풀 전용 스레드에서 돌고 커밋 콜백은 상한(3초)만 기다린 뒤 미확인 표식을 달고 돌아온다. 그 사이 무관한 플랫폼 트랜잭션도 곧바로
   * 커넥션을 얻는다. 소유자 풀이 풀리면 계속 돌던 끊기가 세션을 끊고, 실행기의 전체 동기화(잠금 안에서 다시 끊음)도 넓힌다. 고치기 전에는 커밋 콜백이 바깥 커넥션을 쥔
   * 채 소유자 풀을 30초 기다려 시간 단언이 깨졌다.
   */
  @Test
  void levelsChanged_ownerPoolBusy_commitsDoNotStall_andSessionsAreKilledOnceFree()
      throws Exception {
    int poolSize = dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize();
    assertThat(poolSize).as("전제: 테스트 풀이 작아야 동시 커밋으로 풀을 다 쥘 수 있다").isBetween(1, 4);
    String sens = table("busy", "민감"); // ACL 은 생성 시점(내부) 기준 → 슬롯 2 에 남는 권한
    Connection running = openSlotSession(2);
    Connection ownerHeld = schemaOwnerDataSource.getConnection(); // 소유자 풀(크기 1)을 막는다
    boolean ownerReleased = false;
    try {
      List<Long> elapsed =
          commitConcurrentlyAtPoolSize(
              poolSize,
              i ->
                  () ->
                      events.publishEvent(
                          new SecurityLevelsChangedEvent(
                              DEFAULT_TEST_TENANT_ID,
                              SecurityLevelsChangedEvent.Kind.REORDERED,
                              null)));
      assertThat(elapsed).as("커밋 콜백이 소유자 풀을 30초씩 기다리지 않는다(상한 3초)").allMatch(ms -> ms < 8_000L);
      long start = System.nanoTime();
      TenantRlsTestSupport.runInTenantTransaction(
          fixtureTransactionTemplate, DEFAULT_TEST_TENANT_ID, () -> dsl.fetchValue("select 1"));
      assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
          .as("무관한 플랫폼 트랜잭션도 곧바로 커넥션을 얻는다")
          .isLessThan(2_000L);
      assertThat(queryState(running)).as("전제: 소유자 풀이 막혀 아직 못 끊었다").isNull();

      ownerHeld.close();
      ownerReleased = true;
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
      while (queryState(running) == null && System.nanoTime() < deadline) {
        Thread.sleep(100);
      }
      assertThat(queryState(running)).as("소유자 풀이 풀리면 계속 돌던 끊기가 세션을 끊는다").isNotNull();
      awaitPythonReadGrantSync();
      assertThat(selectAs(2, sens)).as("실행기의 전체 동기화도 끝까지 돌았다").isEqualTo("42501");
      assertThat(selectAs(3, sens)).isNull();
    } finally {
      if (!ownerReleased) {
        ownerHeld.close();
      }
      running.close();
    }
  }

  // ------------------------------------------------ 서비스 발행 → 리스너 종단(R2, B 병합 후)

  /**
   * 데이터셋 등급 변경을 흐름 B 의 서비스(DatasetSecurityService.changeLevel)로만 한다 — 테스트는 이벤트를 발행하지도, 동기화를 부르지도
   * 않는다. 커밋 후 B 의 MANUAL 발행 → C 리스너가 그 테이블을 재동기화해 공개→민감 상향이 슬롯 1 접근을 즉시 42501 로 뒤집고 슬롯 3 은 유지한다(JIT
   * 를 기다리지 않는다). B 의 changeLevel 발행 줄을 지우면 슬롯 1 이 계속 읽어 실패한다(변이).
   */
  @Test
  void changeLevel_viaService_revokesLowerSlotAfterCommit() {
    String t = table("chg", "공개");
    long id = lastDatasetId();
    sync.syncTenant();
    assertThat(selectAs(1, t)).as("출발: 공개(위치 1)는 슬롯 1 이 읽는다").isNull();

    // 변경자 자격: '민감' rank(본인 자격보다 높은 등급으로는 지정할 수 없다). 허용 목록 등급으로 가지 않으므로 역할·권한 집합은 비워도 된다.
    int sensRank =
        TenantRlsTestSupport.runInTenantTransaction(
            fixtureTransactionTemplate,
            DEFAULT_TEST_TENANT_ID,
            () -> levelRepository.findById(fx.levelId("민감")).orElseThrow().rank());
    Clearance classifier =
        new Clearance(owner, DEFAULT_TEST_TENANT_ID, sensRank, Set.of(), false, Set.of());
    datasetSecurityService.changeLevel(
        id, new ChangeDatasetLevelRequest(fx.levelId("민감"), null), classifier);
    awaitPythonReadGrantSync(); // 리스너 동기화는 커밋 뒤 실행기에서 돈다(WD-624)

    assertThat(selectAs(1, t)).as("민감(위치 3)으로 상향 → 슬롯 1 거부").isEqualTo("42501");
    assertThat(selectAs(2, t)).isEqualTo("42501");
    assertThat(selectAs(3, t)).as("슬롯 3 은 유지").isNull();
  }

  /**
   * 등급 순서 변경을 흐름 B 의 서비스(SecurityLevelService.applyReorder)로만 한다 — 테스트 본문은 이벤트를 직접 발행하지 않고 동기화도 부르지
   * 않는다. 커밋 후 B 의 REORDERED 발행 → 리스너가 테넌트 전체를 새 위치로 맞춘다. B 의 applyReorder 발행 줄을 지우면 ACL 이 옛 순서에 남아
   * 실패한다(변이). 원복은 다른 테스트를 위해 직접 발행까지 해서 확실히 되돌린다.
   */
  @Test
  void applyReorder_viaService_flipsSlotAccessAfterCommit() {
    String pub = table("rs_pub", "공개");
    String internal = table("rs_int", "내부");
    sync.syncTenant();
    assertThat(selectAs(1, pub)).as("출발: 공개(위치 1)는 슬롯 1 이 읽는다").isNull();
    assertThat(selectAs(1, internal)).as("출발: 내부(위치 2)는 슬롯 1 이 못 읽는다").isEqualTo("42501");

    List<Long> original =
        TenantRlsTestSupport.runInTenantTransaction(
            fixtureTransactionTemplate,
            DEFAULT_TEST_TENANT_ID,
            () -> levelRepository.findAll().stream().map(LevelPolicy::id).toList());
    assertThat(original.subList(0, 2))
        .as("전제: 맨 아래 두 등급이 공개·내부")
        .containsExactly(fx.levelId("공개"), fx.levelId("내부"));
    List<Long> swapped = new ArrayList<>(original);
    swapped.set(0, original.get(1));
    swapped.set(1, original.get(0));
    try {
      levelService.applyReorder(swapped, owner); // 서비스 자체 트랜잭션 — 커밋 후 B 의 발행이 리스너에 닿는다
      awaitPythonReadGrantSync(); // 리스너 동기화는 커밋 뒤 실행기에서 돈다(WD-624)
      assertThat(selectAs(1, pub)).as("공개가 위치 2 로 → 슬롯 1 거부").isEqualTo("42501");
      assertThat(selectAs(2, pub)).isNull();
      assertThat(selectAs(1, internal)).as("내부가 위치 1 로 → 슬롯 1 허용").isNull();
    } finally {
      reorderAndPublish(
          original,
          new SecurityLevelsChangedEvent(
              DEFAULT_TEST_TENANT_ID, SecurityLevelsChangedEvent.Kind.REORDERED, null));
    }
    assertThat(selectAs(1, pub)).as("원복 후 공개는 다시 슬롯 1 이 읽는다").isNull();
  }

  /** 데이터셋 등급 변경 이벤트 → 커밋 후 그 데이터셋 테이블만 재동기화. */
  @Test
  void datasetLevelChangedEvent_resyncsDatasetAfterCommit() {
    String pub = table("dl", "공개"); // ACL 은 내부 기준 → 슬롯 1 부족
    long id = lastDatasetId();
    assertThat(selectAs(1, pub)).as("출발 상태: 과소권한").isEqualTo("42501");
    DatasetSecurityLevelChangedEvent event =
        new DatasetSecurityLevelChangedEvent(
            DEFAULT_TEST_TENANT_ID,
            id,
            fx.levelId("내부"),
            fx.levelId("공개"),
            DatasetSecurityLevelChangedEvent.Cause.MANUAL);

    publishInTx(event, false);
    assertThat(selectAs(1, pub)).as("롤백된 발행은 리스너가 돌지 않는다").isEqualTo("42501");

    publishInTx(event, true);
    assertThat(selectAs(1, pub)).isNull();
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
    fx.deleteUser(owner);
  }
}
