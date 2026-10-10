package com.smartfirehub.securitylevel;

import static com.smartfirehub.jooq.Tables.DATASET;
import static com.smartfirehub.jooq.Tables.SECURITY_LEVEL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantProvisioningService;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.LevelPolicy;
import com.smartfirehub.securitylevel.access.LevelPolicy.AiPolicy;
import com.smartfirehub.securitylevel.access.LevelPolicy.ExportPolicy;
import com.smartfirehub.securitylevel.access.LevelPolicy.SharePolicy;
import com.smartfirehub.securitylevel.dto.ChangeDatasetLevelRequest;
import com.smartfirehub.securitylevel.dto.DeleteSecurityLevelRequest;
import com.smartfirehub.securitylevel.dto.SecurityLevelRequest;
import com.smartfirehub.securitylevel.event.DatasetSecurityLevelChangedEvent;
import com.smartfirehub.securitylevel.event.DatasetSecurityLevelChangedEvent.Cause;
import com.smartfirehub.securitylevel.event.SecurityLevelsChangedEvent;
import com.smartfirehub.securitylevel.event.SecurityLevelsChangedEvent.Kind;
import com.smartfirehub.securitylevel.repository.SecurityLevelRepository;
import com.smartfirehub.securitylevel.service.DatasetSecurityService;
import com.smartfirehub.securitylevel.service.SecurityLevelService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.PostgresTestContainer;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.support.TestUsers;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalApplicationListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 흐름 C·A 구독 계약(공통 결정 R1·R2) — 데이터셋 등급 변경과 등급 정의 변경이 <b>변경 지점마다 정확히 1번</b>, <b>커밋 뒤에만</b> 구독자에게
 * 도달한다. 수동 변경은 자동 상향 표시를 지운다(계획 결정 15).
 *
 * <p>왜 실제 AFTER_COMMIT 리스너인가: 발행 시점에 기록하는 방식({@code @RecordApplicationEvents})은 롤백된 변경도 세어 "롤백이면
 * 0건"을 증명하지 못한다. 트랜잭션 리스너 어댑터는 활성 트랜잭션이 없으면 아예 호출되지 않으므로 "트랜잭션 안에서 발행했는가"까지 함께 증명한다. 컨텍스트를 새로 띄우지
 * 않도록 {@code @Import} 대신 멀티캐스터에 테스트마다 붙였다 뗀다.
 *
 * <p>등급 생성·삭제·순서 변경은 시스템 ADMIN 의 최상위 등급을 바꾸므로 다른 테스트를 오염시키지 않게 격리 테넌트에서 검증한다.
 */
class SecurityEventsTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private DatasetSecurityService datasetSecurityService;
  @Autowired private SecurityLevelService securityLevelService;
  @Autowired private SecurityLevelRepository levelRepository;
  @Autowired private PlatformTransactionManager txManager;
  @Autowired private ApplicationEventMulticaster multicaster;

  /** 이 테스트 테넌트의 이벤트만 모은다(다른 테스트가 같은 JVM 에서 낸 이벤트 배제). */
  private final List<Object> captured = new CopyOnWriteArrayList<>();

  private ApplicationListener<PayloadApplicationEvent<Object>> listener;
  private long tenantId;
  private long actor;

  @BeforeEach
  void setUp() {
    tenantId = TenantRlsTestSupport.createActiveTenant(dsl, "sev" + System.nanoTime());
    provisioning.provisionDefaults(tenantId);
    String u = "sev" + System.nanoTime() + "@example.com";
    actor =
        TestUsers.createMember(
                dsl, fixtureTransactionTemplate, encoder, u, u, "Password123", "a", tenantId)
            .id();
    listener =
        TransactionalApplicationListener.forPayload(
            TransactionPhase.AFTER_COMMIT,
            payload -> {
              if (payload instanceof DatasetSecurityLevelChangedEvent e && e.tenantId() == tenantId
                  || payload instanceof SecurityLevelsChangedEvent l && l.tenantId() == tenantId) {
                captured.add(payload);
              }
            });
    multicaster.addApplicationListener(listener);
  }

  @AfterEach
  void tearDown() {
    multicaster.removeApplicationListener(listener);
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    TenantRlsTestSupport.cleanupAll(
        () ->
            inTenantFixture(
                tenantId,
                () -> dsl.deleteFrom(DATASET).where(DATASET.TENANT_ID.eq(tenantId)).execute()),
        () -> TestUsers.cleanup(dsl, fixtureTransactionTemplate, actor, tenantId),
        () ->
            TenantRlsTestSupport.deleteProvisionedTenantCascade(
                dsl, fixtureTransactionTemplate, tenantId));
  }

  // ── 데이터셋 등급 변경 ──────────────────────────────────────────────

  @Test
  void manualChange_publishesOnceAfterCommit_andClearsAutoRaisedAt() {
    long ds = insertDataset("내부");
    inTenantFixture(
        tenantId,
        () ->
            dsl.update(DATASET)
                .set(DATASET.SECURITY_LEVEL_AUTO_RAISED_AT, LocalDateTime.now())
                .where(DATASET.ID.eq(ds))
                .execute());
    asTenant(
        () ->
            datasetSecurityService.changeLevel(
                ds, new ChangeDatasetLevelRequest(levelId("민감"), null), topClearance()));
    assertThat(datasetEvents(ds))
        .containsExactly(
            new DatasetSecurityLevelChangedEvent(
                tenantId, ds, levelId("내부"), levelId("민감"), Cause.MANUAL));
    assertThat(levelEvents()).isEmpty();
    LocalDateTime raised =
        inTenantFixture(
            tenantId,
            () ->
                dsl.select(DATASET.SECURITY_LEVEL_AUTO_RAISED_AT)
                    .from(DATASET)
                    .where(DATASET.ID.eq(ds))
                    .fetchOne(DATASET.SECURITY_LEVEL_AUTO_RAISED_AT));
    assertThat(raised).as("수동 변경은 자동 상향 표시를 지운다").isNull();
  }

  /** 같은 등급으로의 변경도 UPDATE·감사를 하므로 이벤트도 1건(구독자 재동기화는 멱등). */
  @Test
  void manualChangeToSameLevel_stillPublishesOnce() {
    long ds = insertDataset("내부");
    asTenant(
        () ->
            datasetSecurityService.changeLevel(
                ds, new ChangeDatasetLevelRequest(levelId("내부"), null), topClearance()));
    assertThat(datasetEvents(ds))
        .containsExactly(
            new DatasetSecurityLevelChangedEvent(
                tenantId, ds, levelId("내부"), levelId("내부"), Cause.MANUAL));
  }

  @Test
  void rolledBackManualChange_publishesNothing() {
    long ds = insertDataset("내부");
    asTenant(
        () ->
            new TransactionTemplate(txManager)
                .executeWithoutResult(
                    s -> {
                      datasetSecurityService.changeLevel(
                          ds, new ChangeDatasetLevelRequest(levelId("민감"), null), topClearance());
                      s.setRollbackOnly();
                    }));
    assertThat(captured).isEmpty();
  }

  @Test
  void autoRaise_publishesOnceWithAutoRaiseCause() {
    long ds = insertDataset("내부");
    asTenant(
        () ->
            datasetSecurityService.raiseForPipelineOutput(
                ds, levelRepository.findById(levelId("민감")).orElseThrow(), actor));
    assertThat(datasetEvents(ds))
        .containsExactly(
            new DatasetSecurityLevelChangedEvent(
                tenantId, ds, levelId("내부"), levelId("민감"), Cause.AUTO_RAISE));
  }

  @Test
  void newPipelineTempAssign_publishesOnceWithTempAssignCause() {
    long ds = insertDataset("내부");
    asTenant(
        () ->
            datasetSecurityService.assignNewPipelineTempLevel(
                ds, levelRepository.findById(levelId("공개")).orElseThrow(), actor));
    assertThat(datasetEvents(ds))
        .containsExactly(
            new DatasetSecurityLevelChangedEvent(
                tenantId, ds, levelId("내부"), levelId("공개"), Cause.PIPELINE_TEMP_ASSIGN));
  }

  @Test
  void cloneInherit_publishesOnceWithCloneInheritCause() {
    long source = insertDataset("민감");
    long copy = insertDataset("내부");
    asTenant(() -> datasetSecurityService.inheritFromSource(source, copy, actor));
    assertThat(datasetEvents(copy))
        .containsExactly(
            new DatasetSecurityLevelChangedEvent(
                tenantId, copy, levelId("내부"), levelId("민감"), Cause.CLONE_INHERIT));
    assertThat(datasetEvents(source)).isEmpty();
  }

  // ── 등급 정의 변경 ─────────────────────────────────────────────────

  @Test
  void levelCreate_publishesCreatedOnce() {
    long id = asTenant(() -> securityLevelService.create(req("극비", false), actor)).id();
    assertThat(levelEvents())
        .containsExactly(new SecurityLevelsChangedEvent(tenantId, Kind.CREATED, id));
  }

  @Test
  void rolledBackLevelCreate_publishesNothing() {
    asTenant(
        () ->
            new TransactionTemplate(txManager)
                .executeWithoutResult(
                    s -> {
                      securityLevelService.create(req("극비", false), actor);
                      s.setRollbackOnly();
                    }));
    assertThat(captured).isEmpty();
  }

  /**
   * 등급 상한 10(흐름 C, WD-29) — 11번째 생성은 SECURITY_LEVEL_LIMIT_EXCEEDED 로 거부되고 CREATED 이벤트를 내지 않는다(상한
   * 검사가 발행보다 앞, 거부는 롤백). 상한 검사를 지우면 11번째가 만들어져 CREATED 1건이 잡혀 실패한다(변이).
   */
  @Test
  void levelCreate_beyondLimit_isRejectedAndPublishesNothing() {
    int existing = asTenant(() -> levelRepository.findAll().size());
    for (int i = existing; i < SecurityLevelService.MAX_LEVELS; i++) {
      String name = "상한" + i;
      asTenant(() -> securityLevelService.create(req(name, false), actor));
    }
    captured.clear();
    assertThatThrownBy(() -> asTenant(() -> securityLevelService.create(req("열한째", false), actor)))
        .isInstanceOf(CodedApiException.class)
        .satisfies(
            e ->
                assertThat(((CodedApiException) e).code())
                    .isEqualTo("SECURITY_LEVEL_LIMIT_EXCEEDED"));
    assertThat(captured).isEmpty();
    assertThat(asTenant(() -> levelRepository.findAll().size()))
        .isEqualTo(SecurityLevelService.MAX_LEVELS);
  }

  /**
   * 동시 생성 직렬화(CR1) — 등급 9개에서 두 생성이 겹치면 하나만 성공하고 다른 하나는 상한(SECURITY_LEVEL_LIMIT_EXCEEDED)으로 거부되며, 최종
   * 10개· CREATED 1건이다. 인터리빙을 결정적으로 만든다: A 가 삽입 후 트랜잭션을 연 채 멈추고, B 가 잠금 대기에 들어간 것을 pg_stat_activity
   * 로 확인한 뒤 A 를 커밋한다. 변이: 생성의 잠금을 지우면 B 는 A 와 같은 nextRank 로 순위 유일 제약에서 기다리다 DuplicateKeyException
   * 으로 끝나 코드 단언이 실패한다(상한 검사가 A 의 미커밋 행을 못 본다).
   */
  @Test
  void concurrentCreate_atNineLevels_onlyOneSucceeds_otherHitsLimit() throws Exception {
    int existing = asTenant(() -> levelRepository.findAll().size());
    for (int i = existing; i < SecurityLevelService.MAX_LEVELS - 1; i++) {
      String name = "동시채움" + i;
      asTenant(() -> securityLevelService.create(req(name, false), actor));
    }
    captured.clear();
    TransactionTemplate tt = new TransactionTemplate(txManager);
    CountDownLatch aInserted = new CountDownLatch(1);
    CountDownLatch releaseA = new CountDownLatch(1);
    AtomicReference<Throwable> aError = new AtomicReference<>();
    AtomicReference<Throwable> bError = new AtomicReference<>();
    Thread a =
        new Thread(
            () -> {
              try {
                TenantContext.runScoped(
                    tenantId,
                    () ->
                        tt.executeWithoutResult(
                            status -> {
                              securityLevelService.create(req("동시A", false), actor);
                              aInserted.countDown();
                              try {
                                releaseA.await(30, TimeUnit.SECONDS);
                              } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                              }
                            }));
              } catch (Throwable e) {
                aError.set(e);
                aInserted.countDown();
              }
            });
    Thread b =
        new Thread(
            () -> {
              try {
                asTenant(() -> securityLevelService.create(req("동시B", false), actor));
              } catch (Throwable e) {
                bError.set(e);
              }
            });
    a.start();
    try {
      assertThat(aInserted.await(30, TimeUnit.SECONDS)).isTrue();
      assertThat(aError.get()).isNull();
      b.start();
      // B 가 잠금(advisory 또는 — 변이 시 — 순위 유일 인덱스) 대기에 들어갈 때까지 기다린다. 테스트 풀(2)은 A·B 가 쥐고 있어 소유자 롤로 따로
      // 붙는다(슈퍼유저라 다른 롤 세션의 대기 상태도 보인다).
      long deadline = System.currentTimeMillis() + 15_000;
      boolean bWaiting = false;
      try (Connection watcher =
              DriverManager.getConnection(
                  PostgresTestContainer.INSTANCE.getJdbcUrl(),
                  PostgresTestContainer.INSTANCE.getUsername(),
                  PostgresTestContainer.INSTANCE.getPassword());
          PreparedStatement ps =
              watcher.prepareStatement(
                  "select exists (select 1 from pg_stat_activity where datname = current_database()"
                      + " and wait_event_type = 'Lock' and (query ilike '%pg_advisory_xact_lock%'"
                      + " or query ilike '%insert into%security_level%'))")) {
        while (!bWaiting && System.currentTimeMillis() < deadline) {
          try (ResultSet rs = ps.executeQuery()) {
            rs.next();
            bWaiting = rs.getBoolean(1);
          }
          if (!bWaiting) {
            Thread.sleep(50);
          }
        }
      }
      assertThat(bWaiting).as("전제: B 가 A 커밋을 기다린다").isTrue();
    } finally {
      releaseA.countDown();
      a.join(30_000);
      b.join(30_000);
    }
    assertThat(aError.get()).isNull();
    assertThat(bError.get())
        .isInstanceOf(CodedApiException.class)
        .satisfies(
            e ->
                assertThat(((CodedApiException) e).code())
                    .isEqualTo("SECURITY_LEVEL_LIMIT_EXCEEDED"));
    assertThat(asTenant(() -> levelRepository.findAll().size()))
        .isEqualTo(SecurityLevelService.MAX_LEVELS);
    assertThat(levelEvents()).hasSize(1);
  }

  /** allowlist_required 만 바꾼 수정도 UPDATED 1건 — 구독자(C)는 허용 목록 등급 여부로 슬롯 범위를 다시 계산한다. */
  @Test
  void levelUpdate_allowlistOnly_publishesUpdatedOnce() {
    long sensitive = levelId("민감");
    // 현재 정책을 그대로 두고 allowlist_required 만 뒤집는다(시드 기본값 PERMISSION·SELF_HOSTED_ONLY·감사 등은 유지).
    LevelPolicy cur = asTenant(() -> levelRepository.findById(sensitive).orElseThrow());
    SecurityLevelRequest allowlistOnly =
        new SecurityLevelRequest(
            cur.name(),
            !cur.allowlistRequired(),
            cur.adminBypass(),
            cur.exportPolicy(),
            cur.aiPolicy(),
            cur.sharePolicy(),
            cur.auditAccess(),
            null);
    asTenant(() -> securityLevelService.update(sensitive, allowlistOnly, actor));
    assertThat(levelEvents())
        .containsExactly(new SecurityLevelsChangedEvent(tenantId, Kind.UPDATED, sensitive));
  }

  /** 사용 중 등급 삭제의 데이터셋 일괄 이동은 데이터셋별 이벤트 0건, DELETED 1건으로 알린다(계획 결정 14). */
  @Test
  void levelDelete_withMove_publishesDeletedOnce_andNoDatasetEvents() {
    long publicLevel = levelId("공개");
    long ds = insertDataset("공개");
    asTenant(
        () ->
            securityLevelService.delete(
                publicLevel, new DeleteSecurityLevelRequest(levelId("내부"), null), actor));
    assertThat(datasetEvents(ds)).isEmpty();
    assertThat(captured)
        .containsExactly(new SecurityLevelsChangedEvent(tenantId, Kind.DELETED, publicLevel));
  }

  @Test
  void levelReorder_publishesReorderedOnce_withNullLevelId() {
    List<Long> order = List.of(levelId("공개"), levelId("민감"), levelId("내부"), levelId("기밀"));
    asTenant(() -> securityLevelService.applyReorder(order, actor));
    assertThat(levelEvents())
        .containsExactly(new SecurityLevelsChangedEvent(tenantId, Kind.REORDERED, null));
  }

  // ── 픽스처 ────────────────────────────────────────────────────────

  private List<DatasetSecurityLevelChangedEvent> datasetEvents(long datasetId) {
    return captured.stream()
        .filter(DatasetSecurityLevelChangedEvent.class::isInstance)
        .map(DatasetSecurityLevelChangedEvent.class::cast)
        .filter(e -> e.datasetId() == datasetId)
        .toList();
  }

  private List<SecurityLevelsChangedEvent> levelEvents() {
    return captured.stream()
        .filter(SecurityLevelsChangedEvent.class::isInstance)
        .map(SecurityLevelsChangedEvent.class::cast)
        .toList();
  }

  /** 변경자 자격 — 최상위(기밀) rank. 허용 목록 필요 등급으로 가지 않는 경로만 쓰므로 역할·권한 집합은 비워도 된다. */
  private Clearance topClearance() {
    int rank =
        inTenantFixture(
            tenantId,
            () ->
                dsl.select(SECURITY_LEVEL.RANK)
                    .from(SECURITY_LEVEL)
                    .where(SECURITY_LEVEL.NAME.eq("기밀"))
                    .fetchSingle(SECURITY_LEVEL.RANK));
    return new Clearance(actor, tenantId, rank, Set.of(), true, Set.of());
  }

  private <T> T asTenant(Supplier<T> s) {
    return TenantContext.runScopedGet(tenantId, s);
  }

  private void asTenant(Runnable r) {
    TenantContext.runScoped(tenantId, r);
  }

  private long levelId(String name) {
    return inTenantFixture(
        tenantId,
        () ->
            dsl.select(SECURITY_LEVEL.ID)
                .from(SECURITY_LEVEL)
                .where(SECURITY_LEVEL.NAME.eq(name))
                .fetchSingle(SECURITY_LEVEL.ID));
  }

  private long insertDataset(String levelName) {
    long level = levelId(levelName);
    return inTenantFixture(
        tenantId,
        () ->
            dsl.insertInto(DATASET)
                .set(DATASET.NAME, "sev_ds_" + System.nanoTime())
                .set(DATASET.TABLE_NAME, "sev_ds_" + System.nanoTime())
                .set(DATASET.STORAGE_TYPE, "TABLE")
                .set(DATASET.ORIGIN_TYPE, "SOURCE")
                .set(DATASET.CREATED_BY, actor)
                .set(DATASET.SECURITY_LEVEL_ID, level)
                .returning(DATASET.ID)
                .fetchSingle(DATASET.ID));
  }

  private static SecurityLevelRequest req(String name, boolean allowlistRequired) {
    return new SecurityLevelRequest(
        name,
        allowlistRequired,
        false,
        ExportPolicy.ALLOW,
        AiPolicy.ALL,
        SharePolicy.ALLOW,
        false,
        null);
  }
}
