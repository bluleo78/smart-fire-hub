package com.smartfirehub.securitylevel.pythonread;

import static com.smartfirehub.jooq.Tables.DATASET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.smartfirehub.dataset.dto.CloneDatasetRequest;
import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantPipelineRole;
import com.smartfirehub.securitylevel.access.LevelPolicy;
import com.smartfirehub.securitylevel.event.DatasetSecurityLevelChangedEvent;
import com.smartfirehub.securitylevel.event.SecurityLevelsChangedEvent;
import com.smartfirehub.securitylevel.repository.SecurityLevelRepository;
import com.smartfirehub.securitylevel.service.SecurityLevelService;
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
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicReference;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
  @Autowired private PasswordEncoder encoder;
  @Autowired private DatasetService datasetService;
  @Autowired private DataTableService dataTableService;
  @Autowired private PythonReadGrantSync sync;
  @Autowired private ApplicationEventPublisher events;
  @Autowired private SecurityLevelService levelService;
  @Autowired private SecurityLevelRepository levelRepository;

  @Value("${app.pipeline.role-password-secret}")
  private String secret;

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

  private long lastDatasetId() {
    return datasets.get(datasets.size() - 1);
  }

  /** 슬롯 롤 10개의 SELECT 를 전부 걷는다(런타임 롤 = 테이블 소유자). "GRANT 가 없는 테이블" 출발 상태를 만든다. */
  private void revokeAllSlots(String table) {
    StringJoiner roles = new StringJoiner(", ");
    for (int k = 1; k <= TenantPipelineRole.PYTHON_READ_SLOTS; k++) {
      roles.add(TenantPipelineRole.pythonReadRoleName(DEFAULT_TEST_TENANT_ID, k));
    }
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> dsl.execute("REVOKE ALL ON " + DataSchema.qualify(table) + " FROM " + roles));
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

  /** 발행 스레드의 테넌트를 일부러 엉뚱하게 두고, 커밋(또는 롤백)되는 트랜잭션 안에서 이벤트를 발행한다. */
  private void publishInTx(Object event, boolean commit) {
    TenantContext.runScoped(
        WRONG_AMBIENT_TENANT,
        () ->
            fixtureTransactionTemplate.executeWithoutResult(
                status -> {
                  events.publishEvent(event);
                  if (!commit) {
                    status.setRollbackOnly();
                  }
                }));
  }

  // ---------------------------------------------------------------- 테이블 훅

  /** 맞바꿈(RENAME)은 ACL 을 잃는다 — 훅이 트랜잭션 밖 호출이면 즉시 복원한다(수동 동기화 없음). 훅 제거 시 실패(변이). */
  @Test
  void swapTable_restoresSlotGrantsWithoutManualSync() {
    String pub = table("pub", "공개");
    sync.syncTenant();
    assertThat(selectAs(1, pub)).as("대조군: 맞바꿈 전에는 읽는다").isNull();
    dataTableService.createTempTable(pub);
    dataTableService.swapTable(pub); // 트랜잭션 밖 호출 → 즉시 실행 분기
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
    assertThat(selectAs(1, pub)).as("롤백된 트랜잭션의 예약은 실행되지 않는다").isEqualTo("42501");

    // 커밋 → 본문 안에서는 아직 없고, 커밋 후 생긴다.
    fixtureTransactionTemplate.executeWithoutResult(
        status -> {
          sync.syncTableAfterCommit(pub);
          insideBody.set(selectAs(1, pub));
        });
    assertThat(insideBody.get()).as("커밋 전에는 동기화하지 않는다").isEqualTo("42501");
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
    assertThat(selectAs(1, pub)).as("컨텍스트 없으면 동기화는 건너뛴다(다음 동기화가 회복)").isEqualTo("42501");
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
   * 테이블은 위치 2 가 되어 슬롯 1 이 못 읽고, 내부 테이블은 위치 1 이 되어 슬롯 1 이 읽는다. 발행은 흐름 B 소유라(R2) 서비스가 아니라 테스트가 직접 한다.
   * 리스너 본문을 지우면 ACL 이 옛 순서에 남아 실패한다(변이).
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

  /** 테넌트 트랜잭션 안에서 순서를 적용하고 같은 트랜잭션에서 이벤트를 발행한다(커밋 후 리스너 실행). */
  private void reorderAndPublish(List<Long> orderedIds, SecurityLevelsChangedEvent event) {
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> {
          levelService.applyReorder(orderedIds, owner);
          events.publishEvent(event);
        });
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
