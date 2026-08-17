package com.smartfirehub.tenant;

import static com.smartfirehub.jooq.Tables.ASYNC_JOB;
import static com.smartfirehub.jooq.Tables.PIPELINE;
import static com.smartfirehub.jooq.Tables.PIPELINE_EXECUTION;
import static com.smartfirehub.jooq.Tables.PIPELINE_TRIGGER;
import static com.smartfirehub.jooq.Tables.TRIGGER_EVENT;
import static com.smartfirehub.jooq.Tables.UPLOADED_FILES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.dashboard.job.PipelineExecutionTtlJob;
import com.smartfirehub.dataimport.service.StagingTableCleanupService;
import com.smartfirehub.file.service.FileCleanupService;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.job.service.AsyncJobCleanupService;
import com.smartfirehub.pipeline.service.TriggerEventCleanupService;
import com.smartfirehub.pipeline.service.TriggerEventService;
import com.smartfirehub.proactive.service.MetricPollerService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.jooq.Configuration;
import org.jooq.DSLContext;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;
import org.jooq.ExecuteListenerProvider;
import org.jooq.JSONB;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 정리 스케줄러 4건 + 폴러 2건의 배경 경로가 (1) ACTIVE 테넌트를 순회하고 (2) DB 접근을 트랜잭션 안에서
 * 하는지 고정한다(P2-b Task 8).
 *
 * <p><b>이 클래스에 클래스 레벨 {@code @Transactional} 은 없다 — 의도된 것이다.</b> 붙이는 순간 테스트
 * 트랜잭션이 GUC 를 공급해 운영에 없는 조건을 만들고, 이 파일이 잡아야 할 "잡이 스스로 트랜잭션을 열지
 * 않는다" 는 결함이 구조적으로 보이지 않게 된다. 픽스처만 {@link
 * TenantRlsTestSupport#runInTenantTransaction} 으로 감싸고, 검증 대상인 잡 호출은 트랜잭션 밖에 둔다.
 * 롤백이 없으므로 심은 행은 {@link #cleanup()} 에서 직접 지운다 — 공유 테스트 DB 의 남의 행은 건드리지
 * 않는다.
 *
 * <p><b>왜 "양쪽 다 지워졌는가" 단언만으로는 부족한가(반드시 읽을 것)</b>: P2-b 대상 테이블
 * (uploaded_files·async_job·trigger_event·pipeline_execution)은 V93 에서 {@code tenant_id} 만 받았고
 * <b>정책(RLS)은 V96 에 있다</b>(V93 헤더 주석에 명시). 정책이 없는 동안 이 잡들의 DELETE 는 순회가
 * 있든 없든, 트랜잭션이 있든 없든 전 테넌트 행을 지운다 — 즉 브리프의 "A·B 양쪽이 지워졌는가" 형태는
 * <b>오늘은 공허하게 통과</b>한다(배선을 되돌려도 실패하지 않는다). 그래서 그 행위 단언은 V96 이후를
 * 위한 회귀 가드로 남겨 두고, 오늘 실제로 배선을 판별하는 단언은 {@link SqlProbe} 로 한다: 잡이 그
 * 테이블을 만지는 순간의 (a) 트랜잭션 활성 여부와 (b) 테넌트 컨텍스트를 기록해, 순회를 되돌리면
 * "테넌트 A·B 둘 다 관측" 이 깨지고 {@code TransactionTemplate} 을 되돌리면 "항상 트랜잭션 안" 이
 * 깨지게 한다.
 */
class CleanupSchedulerTenantTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private TransactionTemplate tx;

  @Autowired private FileCleanupService fileCleanupService;
  @Autowired private AsyncJobCleanupService asyncJobCleanupService;
  @Autowired private TriggerEventCleanupService triggerEventCleanupService;
  @Autowired private PipelineExecutionTtlJob pipelineExecutionTtlJob;
  @Autowired private MetricPollerService metricPollerService;
  @Autowired private TriggerEventService triggerEventService;
  @Autowired private StagingTableCleanupService stagingTableCleanupService;

  private final SqlProbe probe = new SqlProbe();
  private ExecuteListenerProvider[] originalListenerProviders;

  private long tenantA;
  private long tenantB;
  private Long userA;
  private Long userB;
  private Long pipelineA;
  private Long pipelineB;
  private final List<Long> uploadedFileIds = new ArrayList<>();
  private final List<String> asyncJobIds = new ArrayList<>();
  private Long proactiveJobId;

  /**
   * 내가 만들 고아 staging 테이블 이름. 스윕 정규식({@code stg_import_} + 정확히 32자리 hex)을 만족해야
   * 대상이 되므로 UUID 의 hex 32자를 쓴다. 매 테스트마다 새로 뽑아 <b>내가 만든 것만</b> 지운다 —
   * 공유 테스트 DB 에는 다른 세션의 테이블이 있을 수 있다.
   */
  private String stagingTableA;

  private String stagingTableB;

  @BeforeEach
  void setUp() {
    tenantA = TenantRlsTestSupport.createActiveTenant(dsl, "cleanup-a");
    tenantB = TenantRlsTestSupport.createActiveTenant(dsl, "cleanup-b");
    userA = TenantRlsTestSupport.insertUser(dsl, "cleanupa");
    userB = TenantRlsTestSupport.insertUser(dsl, "cleanupb");
    pipelineA = inTenant(tenantA, () -> insertPipeline(userA));
    pipelineB = inTenant(tenantB, () -> insertPipeline(userB));
    stagingTableA = "stg_import_" + UUID.randomUUID().toString().replace("-", "");
    stagingTableB = "stg_import_" + UUID.randomUUID().toString().replace("-", "");

    // 프로브를 공유 DSLContext 설정에 임시로 끼운다. 이 프로젝트의 DSLContext 는
    // PipelineSandboxDataSourceConfig 가 DefaultConfiguration 으로 직접 만들므로
    // ExecuteListenerProvider 빈을 주워가지 않는다 — 그래서 빈 등록이 아니라 런타임 삽입이다.
    // 테스트는 순차 실행이며 @AfterEach 에서 원래 배열로 되돌려 다른 테스트에 영향을 남기지 않는다.
    Configuration configuration = dsl.configuration();
    originalListenerProviders = configuration.executeListenerProviders();
    ExecuteListenerProvider[] extended =
        Arrays.copyOf(originalListenerProviders, originalListenerProviders.length + 1);
    extended[originalListenerProviders.length] = new DefaultExecuteListenerProvider(probe);
    configuration.set(extended);
  }

  @AfterEach
  void cleanup() {
    dsl.configuration().set(originalListenerProviders);
    probe.disarm();

    // 이 클래스에는 롤백이 없으므로 내가 만든 물리 테이블은 무조건 지운다 — 단언이 도중에 깨져도
    // 공유 테스트 DB 에 누수되지 않게 한다(스윕이 이미 지웠다면 IF EXISTS 가 무해하게 통과).
    dropStagingTableIfExists(stagingTableA);
    dropStagingTableIfExists(stagingTableB);

    // 내가 만든 픽스처만 지운다(공유 테스트 DB).
    inTenant(
        tenantA,
        () -> {
          deleteMyRows(pipelineA);
          return null;
        });
    inTenant(
        tenantB,
        () -> {
          deleteMyRows(pipelineB);
          return null;
        });
    TenantRlsTestSupport.deleteUser(dsl, userA);
    TenantRlsTestSupport.deleteUser(dsl, userB);
    TenantRlsTestSupport.deleteTenants(dsl, tenantA, tenantB);
    TenantContext.clear();
  }

  private void deleteMyRows(Long pipelineId) {
    if (proactiveJobId != null) {
      // metric_snapshot·anomaly_event 는 job FK CASCADE 로 함께 사라진다.
      dsl.deleteFrom(table(name("proactive_job")))
          .where(field(name("id"), Long.class).eq(proactiveJobId))
          .execute();
      proactiveJobId = null;
    }
    // dataset 은 RLS 대상이라 이 테넌트 컨텍스트에서 내 테넌트 행만 지워진다.
    dsl.deleteFrom(table(name("dataset"))).execute();
    if (!uploadedFileIds.isEmpty()) {
      dsl.deleteFrom(UPLOADED_FILES).where(UPLOADED_FILES.ID.in(uploadedFileIds)).execute();
    }
    if (!asyncJobIds.isEmpty()) {
      dsl.deleteFrom(ASYNC_JOB).where(ASYNC_JOB.ID.in(asyncJobIds)).execute();
    }
    // 파이프라인을 지우면 실행·트리거·트리거 이벤트가 FK CASCADE 로 함께 사라진다.
    dsl.deleteFrom(PIPELINE).where(PIPELINE.ID.eq(pipelineId)).execute();
  }

  // ── 정리 잡 4건 ────────────────────────────────────────────────────────
  //
  // 각 테스트는 두 층으로 단언한다: 행위(A·B 양쪽 삭제 + 미만료 보존)와 배선(프로브).
  //
  // <b>V96(정책) 적용 시 할 일</b>: 정책이 붙는 즉시 이 파일을 다시 돌려, 행위 단언이 그때부터 실제로
  // 크로스테넌트 삭제·무동작을 판별하는지 확인하라(순회를 되돌리는 변이로 검증하면 된다). 정책 이전에는
  // 행위 단언이 배선을 되돌려도 통과한다 — 클래스 javadoc 의 설명 참조.
  //
  // <b>프로브는 그때도 제거하지 말 것</b>: 행위 단언은 GUC 주입 메커니즘 자체를 검증하지 못한다
  // (정책이 있어도 테스트가 트랜잭션을 대신 열어주면 통과한다). 두 층은 서로를 대체하지 않는다.

  @Test
  @DisplayName("만료 업로드 파일 정리: A·B 양쪽 만료 행을 지우고 미만료 행은 남긴다 + 테넌트별 트랜잭션")
  void fileCleanupIteratesTenantsInTransaction() {
    Long expiredA = inTenant(tenantA, () -> insertUploadedFile(userA, hoursAgo(2)));
    Long expiredB = inTenant(tenantB, () -> insertUploadedFile(userB, hoursAgo(2)));

    probe.arm();
    fileCleanupService.cleanupExpiredFiles();
    probe.disarm();

    // 행위 단언: V96(정책) 이후에는 순회가 빠지면 여기서 남고, 순회가 잘못되면 한쪽만 사라진다.
    assertThat(rowExists(tenantA, expiredA)).as("A 의 만료 행이 지워져야 한다").isFalse();
    assertThat(rowExists(tenantB, expiredB)).as("B 의 만료 행이 지워져야 한다").isFalse();

    // 배선 단언: 오늘 실제로 결함을 판별하는 부분.
    assertTouchedInTransactionForBothTenants("uploaded_files", "FileCleanupService");

    Long freshA = inTenant(tenantA, () -> insertUploadedFile(userA, hoursLater(2)));
    fileCleanupService.cleanupExpiredFiles();
    assertThat(rowExists(tenantA, freshA)).as("만료되지 않은 행은 남아야 한다").isTrue();
  }

  @Test
  @DisplayName("오래된 async_job 정리: A·B 양쪽을 지우고 최근 행은 남긴다 + 테넌트별 트랜잭션")
  void asyncJobDeleteOldIteratesTenantsInTransaction() {
    String oldA = inTenant(tenantA, () -> insertAsyncJob(userA, "COMPLETED", daysAgo(40)));
    String oldB = inTenant(tenantB, () -> insertAsyncJob(userB, "COMPLETED", daysAgo(40)));

    probe.arm();
    asyncJobCleanupService.deleteOldJobs();
    probe.disarm();

    assertThat(asyncJobExists(tenantA, oldA)).as("A 의 오래된 잡이 지워져야 한다").isFalse();
    assertThat(asyncJobExists(tenantB, oldB)).as("B 의 오래된 잡이 지워져야 한다").isFalse();
    assertTouchedInTransactionForBothTenants("async_job", "AsyncJobCleanupService.deleteOldJobs");

    String freshA = inTenant(tenantA, () -> insertAsyncJob(userA, "COMPLETED", LocalDateTime.now()));
    asyncJobCleanupService.deleteOldJobs();
    assertThat(asyncJobExists(tenantA, freshA)).as("보존 기간 내 잡은 남아야 한다").isTrue();
  }

  @Test
  @DisplayName("좀비 async_job 실패 처리: A·B 양쪽을 FAILED 로 바꾸고 최근 잡은 건드리지 않는다")
  void asyncJobFailStaleIteratesTenantsInTransaction() {
    String staleA = inTenant(tenantA, () -> insertAsyncJob(userA, "PROCESSING", daysAgo(1)));
    String staleB = inTenant(tenantB, () -> insertAsyncJob(userB, "PROCESSING", daysAgo(1)));
    String freshA =
        inTenant(tenantA, () -> insertAsyncJob(userA, "PROCESSING", LocalDateTime.now()));

    probe.arm();
    asyncJobCleanupService.failStaleJobs();
    probe.disarm();

    assertThat(asyncJobStage(tenantA, staleA)).as("A 의 좀비 잡이 실패 처리되어야 한다").isEqualTo("FAILED");
    assertThat(asyncJobStage(tenantB, staleB)).as("B 의 좀비 잡이 실패 처리되어야 한다").isEqualTo("FAILED");
    assertThat(asyncJobStage(tenantA, freshA)).as("진행 중인 최근 잡은 그대로여야 한다").isEqualTo("PROCESSING");
    assertTouchedInTransactionForBothTenants("async_job", "AsyncJobCleanupService.failStaleJobs");
  }

  @Test
  @DisplayName("오래된 trigger_event 정리: A·B 양쪽을 지우고 최근 이벤트는 남긴다 + 테넌트별 트랜잭션")
  void triggerEventCleanupIteratesTenantsInTransaction() {
    Long oldA = inTenant(tenantA, () -> insertTriggerEvent(pipelineA, userA, daysAgo(100)));
    Long oldB = inTenant(tenantB, () -> insertTriggerEvent(pipelineB, userB, daysAgo(100)));

    probe.arm();
    triggerEventCleanupService.cleanupOldEvents();
    probe.disarm();

    assertThat(triggerEventExists(tenantA, oldA)).as("A 의 오래된 이벤트가 지워져야 한다").isFalse();
    assertThat(triggerEventExists(tenantB, oldB)).as("B 의 오래된 이벤트가 지워져야 한다").isFalse();
    assertTouchedInTransactionForBothTenants("trigger_event", "TriggerEventCleanupService");

    Long freshA = inTenant(tenantA, () -> insertTriggerEvent(pipelineA, userA, LocalDateTime.now()));
    triggerEventCleanupService.cleanupOldEvents();
    assertThat(triggerEventExists(tenantA, freshA)).as("보존 기간 내 이벤트는 남아야 한다").isTrue();
  }

  @Test
  @DisplayName("pipeline_execution TTL: A·B 양쪽 만료 행을 지우고 최근 행은 남긴다 + 테넌트별 트랜잭션")
  void pipelineExecutionTtlIteratesTenantsInTransaction() {
    Long oldA = inTenant(tenantA, () -> insertExecution(pipelineA, userA, daysAgo(100), "COMPLETED"));
    Long oldB = inTenant(tenantB, () -> insertExecution(pipelineB, userB, daysAgo(100), "COMPLETED"));

    ReflectionTestUtils.setField(pipelineExecutionTtlJob, "retentionDays", 90);
    probe.arm();
    pipelineExecutionTtlJob.runScheduled();
    probe.disarm();

    assertThat(executionExists(tenantA, oldA)).as("A 의 만료 실행이 지워져야 한다").isFalse();
    assertThat(executionExists(tenantB, oldB)).as("B 의 만료 실행이 지워져야 한다").isFalse();
    assertTouchedInTransactionForBothTenants("pipeline_execution", "PipelineExecutionTtlJob");

    Long freshA =
        inTenant(tenantA, () -> insertExecution(pipelineA, userA, daysAgo(1), "COMPLETED"));
    pipelineExecutionTtlJob.runScheduled();
    assertThat(executionExists(tenantA, freshA)).as("보존 기간 내 실행은 남아야 한다").isTrue();
  }

  // ── 폴러 2건 ──────────────────────────────────────────────────────────
  //
  // 폴러는 삭제가 아니라 읽기 경로다 — GUC 가 없으면 조용히 0행이 되어 예외도 로그도 없이 무동작이
  // 된다. 그래서 "무엇이 지워졌는가" 가 아니라 "그 조회가 테넌트별 트랜잭션 안에서 돌았는가" 를 본다.

  @Test
  @DisplayName("메트릭 폴러: proactive_job 조회가 테넌트별로 트랜잭션 안에서 돈다")
  void metricPollerReadsInsideTenantTransaction() {
    probe.arm();
    metricPollerService.poll();
    probe.disarm();

    assertTouchedInTransactionForBothTenants("proactive_job", "MetricPollerService.pollMetrics");
  }

  /**
   * SYSTEM 메트릭 수집이 테넌트 트랜잭션 안에서 돌아 <b>실제 값</b>을 본다는 것을 단언한다.
   *
   * <p>이 단언은 프로브가 아니라 수집된 값 자체로 판별한다 — {@code dataset} 은 <b>V88 부터 이미 RLS
   * 가 켜져 있는 테이블</b>이므로, {@code collectSystemMetric} 이 트랜잭션 밖에서 돌면 GUC 가 비어
   * 정책이 전 행을 차단하고 {@code dataset_total_count} 는 <b>언제나 0</b> 이 된다(V96 을 기다리는
   * 미래 대비가 아니라, Task 8 이 실제로 고친 오늘의 결함이다 — 그 전까지 운영에서 조용히 0 이 수집됐다).
   *
   * <p>{@code pollingInterval} 을 0 으로 두는 것이 중요하다: {@code lastPollTime} 키가
   * {@code jobId:metricId} 라 테넌트 스코프가 아니어서, 기본 간격이면 첫 테넌트 패스 뒤 나머지
   * 테넌트가 스킵된다.
   */
  @Test
  @DisplayName("메트릭 폴러: SYSTEM 메트릭이 테넌트 데이터의 실제 값을 수집한다(RLS 적용 dataset)")
  void metricPollerCollectsSystemMetricWithTenantVisibleData() {
    int datasetCountA = 3;
    inTenant(
        tenantA,
        () -> {
          for (int i = 0; i < datasetCountA; i++) {
            insertDataset(userA);
          }
          return null;
        });
    String metricId = "ds-count-" + UUID.randomUUID();
    proactiveJobId = inTenant(tenantA, () -> insertSystemMetricJob(userA, metricId));

    metricPollerService.poll();

    List<Double> collected =
        inTenant(
            tenantA,
            () ->
                dsl.select(field(name("value"), Double.class))
                    .from(table(name("metric_snapshot")))
                    .where(field(name("job_id"), Long.class).eq(proactiveJobId))
                    .and(field(name("metric_id"), String.class).eq(metricId))
                    .fetchInto(Double.class));

    assertThat(collected)
        .as(
            "dataset 은 V88 부터 RLS 대상이다 — collectSystemMetric 이 트랜잭션 밖에서 돌면"
                + " GUC 가 비어 전 행이 차단되고 수집값이 전부 0 이 된다. 수집된 값: %s",
            collected)
        .contains((double) datasetCountA);
  }

  @Test
  @DisplayName("데이터셋 변경 폴러: pipeline_trigger 조회가 테넌트별로 트랜잭션 안에서 돈다")
  void datasetChangePollerReadsInsideTenantTransaction() {
    probe.arm();
    triggerEventService.pollDatasetChanges();
    probe.disarm();

    assertTouchedInTransactionForBothTenants(
        "pipeline_trigger", "TriggerEventService.pollDatasetChangesForTenant");
  }

  /**
   * 고아 staging 스윕이 테넌트를 순회하는지 고정한다(P3-a Task 4 후속 F3).
   *
   * <p><b>왜 이 테스트가 필요했나</b>: {@code findStagingTables()} 가 {@code DataSchema.current()} 로
   * 스키마명을 파생시키게 된 순간, 컨텍스트가 없는 {@code @Scheduled} 진입점은
   * {@code MissingTenantScopeException} 을 던져 스윕이 영구히 무동작이 됐다. 그런데 그 무동작은
   * "정리할 고아가 없었다" 와 구분되지 않아 <b>어떤 테스트도 실패하지 않았다</b>.
   *
   * <p><b>왜 {@code sweepActiveTenants()} 를 부르고 {@code sweepOrphanedStagingTables()} 를 부르지
   * 않는가</b>: 후자는 전역 {@code jobrunr_jobs} 게이트를 먼저 본다. 공유 테스트 DB 에는 다른
   * 세션이 남긴 활성(ENQUEUED) 행이 수백 개 쌓여 있어(실측) 게이트가 상시 닫혀 있고, 그러면 이
   * 테스트는 아무것도 검증하지 못하고 조용히 통과한다. 게이트를 열려면 남의 행을 지워야 하는데
   * 그것은 공유 DB 에서 해서는 안 되는 일이다. 그래서 순회(기계장치)만 떼어 검증한다 —
   * 게이트(정책)는 {@code StagingTableCleanupServiceTest} 가 자기 트랜잭션 안에서 검증한다.
   *
   * <p><b>단언의 의미를 오해하지 말 것</b>: {@code DataSchema.current()} 는 아직 상수 {@code data} 를
   * 돌려주므로 두 테넌트의 스윕은 <b>같은 물리 스키마</b>를 본다. 따라서 "테넌트별 격리" 는 오늘
   * 참이 아니며 그것을 주장하는 단언은 거짓 위안이다. 여기서 고정하는 것은 <b>테넌트 스코프 안에서
   * 회수가 실제로 일어나는가</b>(행위) 와 <b>ACTIVE 테넌트를 실제로 순회했는가</b>(프로브) 두 가지다.
   * 첫 테넌트의 패스가 두 테이블을 모두 지우므로, 순회를 판별하는 것은 프로브 층이다.
   */
  @Test
  @DisplayName("고아 staging 스윕: 테넌트 스코프 안에서 회수되고, ACTIVE 테넌트를 순회한다")
  void stagingSweepIteratesTenantsInTransaction() {
    createStagingTable(stagingTableA);
    createStagingTable(stagingTableB);
    assertThat(stagingTableExists(stagingTableA)).as("픽스처가 실제로 생성돼야 한다").isTrue();
    assertThat(stagingTableExists(stagingTableB)).as("픽스처가 실제로 생성돼야 한다").isTrue();

    probe.arm();
    int dropped = stagingTableCleanupService.sweepActiveTenants();
    probe.disarm();

    // 행위 단언: 테넌트 컨텍스트가 없으면 DataSchema.current() 가 던져 0개가 된다.
    assertThat(dropped).as("고아 두 개가 회수돼야 한다").isGreaterThanOrEqualTo(2);
    assertThat(stagingTableExists(stagingTableA)).as("고아 A 가 회수돼야 한다").isFalse();
    assertThat(stagingTableExists(stagingTableB)).as("고아 B 가 회수돼야 한다").isFalse();

    // 배선 단언: 순회를 되돌리면 관측 테넌트가 하나로 줄어 여기서 깨진다.
    assertTouchedInTransactionForBothTenants(
        "information_schema.tables", "StagingTableCleanupService.sweepCurrentTenant");
  }

  // ── 배선 단언 ─────────────────────────────────────────────────────────

  /**
   * 해당 테이블을 만진 모든 문장이 트랜잭션 안에서 실행됐고, 관측된 테넌트 컨텍스트에 A·B 가 모두
   * 포함되는지 단언한다.
   *
   * <p>공유 테스트 DB 에는 다른 테스트가 남긴 ACTIVE 테넌트도 있을 수 있으므로 "정확히 A·B" 가 아니라
   * "A·B 를 포함" 으로 본다. 트랜잭션 활성 여부를 GUC 주입의 대리 지표로 쓰는 근거는 {@code
   * TenantAwareTransactionManager.doBegin} 이 유일한 주입 지점이라는 것이다(TenantContextGucTest 가
   * 그 사실을 따로 고정한다).
   */
  private void assertTouchedInTransactionForBothTenants(String tableFragment, String label) {
    List<SqlProbe.Observation> hits = probe.observationsFor(tableFragment);
    assertThat(hits)
        .as("%s 가 %s 를 아예 만지지 않았다 — 순회 자체가 돌지 않은 것이다", label, tableFragment)
        .isNotEmpty();
    assertThat(hits)
        .as("%s: %s 접근이 트랜잭션 밖에서 일어났다 — GUC 가 비어 RLS 가 전 행을 차단한다", label, tableFragment)
        .allMatch(SqlProbe.Observation::txActive);

    Set<Long> tenants =
        hits.stream()
            .map(SqlProbe.Observation::tenantId)
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toSet());
    assertThat(tenants)
        .as("%s 가 ACTIVE 테넌트를 순회하지 않았다 — 관측된 테넌트: %s", label, tenants)
        .contains(tenantA, tenantB);
  }

  // ── 프로브 ────────────────────────────────────────────────────────────

  /** 문장 실행 시점의 트랜잭션 활성 여부와 테넌트 컨텍스트를 기록하는 jOOQ 리스너. */
  private static final class SqlProbe implements ExecuteListener {

    record Observation(String sql, boolean txActive, Long tenantId) {}

    private final List<Observation> observations = new CopyOnWriteArrayList<>();
    private volatile boolean armed;

    void arm() {
      observations.clear();
      armed = true;
    }

    void disarm() {
      armed = false;
    }

    List<Observation> observationsFor(String tableFragment) {
      return observations.stream().filter(o -> o.sql().contains(tableFragment)).toList();
    }

    @Override
    public void executeStart(ExecuteContext ctx) {
      if (!armed) {
        return;
      }
      String sql = ctx.sql();
      if (sql == null) {
        return;
      }
      observations.add(
          new Observation(
              sql,
              TransactionSynchronizationManager.isActualTransactionActive(),
              TenantContext.get()));
    }
  }

  // ── 픽스처 ────────────────────────────────────────────────────────────

  private <T> T inTenant(long tenantId, Supplier<T> action) {
    return TenantRlsTestSupport.runInTenantTransaction(tx, tenantId, action);
  }

  private static LocalDateTime daysAgo(int days) {
    return LocalDateTime.now().minusDays(days);
  }

  private static OffsetDateTime hoursAgo(int hours) {
    return OffsetDateTime.now(ZoneOffset.UTC).minusHours(hours);
  }

  private static OffsetDateTime hoursLater(int hours) {
    return OffsetDateTime.now(ZoneOffset.UTC).plusHours(hours);
  }

  private Long insertPipeline(Long ownerId) {
    return dsl.insertInto(PIPELINE)
        .set(PIPELINE.NAME, "cleanup-" + UUID.randomUUID())
        .set(PIPELINE.IS_ACTIVE, true)
        .set(PIPELINE.CREATED_BY, ownerId)
        .set(PIPELINE.CREATED_AT, LocalDateTime.now())
        .returning(PIPELINE.ID)
        .fetchOne()
        .getId();
  }

  /**
   * 고아 staging 테이블을 물리적으로 만든다(임포트 도중 프로세스가 죽은 상황의 재현).
   *
   * <p>테스트 소스는 규약 가드의 대상이 아니므로 물리 스키마명을 직접 쓴다 — 스윕이 실제로 그
   * 스키마를 보는지 검사하는 것이 목적이라 {@code DataSchema} 를 거치면 검사가 순환한다.
   */
  private void createStagingTable(String name) {
    dsl.execute("CREATE TABLE data.\"" + name + "\" (_seq BIGSERIAL, a TEXT)");
  }

  private void dropStagingTableIfExists(String name) {
    if (name != null) {
      dsl.execute("DROP TABLE IF EXISTS data.\"" + name + "\"");
    }
  }

  private boolean stagingTableExists(String name) {
    Long count =
        dsl.fetchOne(
                "SELECT count(*) FROM information_schema.tables "
                    + "WHERE table_schema = 'data' AND table_name = ?",
                name)
            .get(0, Long.class);
    return count != null && count > 0;
  }

  /** RLS(V88) 대상 dataset 행. SYSTEM 메트릭 dataset_total_count 가 세는 대상이다. */
  private void insertDataset(Long ownerId) {
    long suffix = TenantRlsTestSupport.nextTenantId();
    dsl.insertInto(table(name("dataset")))
        .set(field(name("name"), String.class), "정리스케줄러-" + suffix)
        .set(field(name("table_name"), String.class), "tbl_cleanup_" + suffix)
        .set(field(name("storage_type"), String.class), "DOCUMENT")
        .set(field(name("origin_type"), String.class), "SOURCE")
        .set(field(name("created_by"), Long.class), ownerId)
        .execute();
  }

  /** ANOMALY 트리거 + SYSTEM 메트릭(dataset_total_count) 하나만 가진 proactive_job. */
  private Long insertSystemMetricJob(Long ownerId, String metricId) {
    String config =
        """
        {"anomaly":{"sensitivity":"medium","metrics":[{"id":"%s","name":"dataset count",\
        "source":"system","metricKey":"dataset_total_count","pollingInterval":0}]}}"""
            .formatted(metricId);
    return dsl.insertInto(table(name("proactive_job")))
        .set(field(name("user_id"), Long.class), ownerId)
        .set(field(name("name"), String.class), "cleanup-metric-" + metricId)
        .set(field(name("prompt"), String.class), "test")
        .set(field(name("config"), JSONB.class), JSONB.valueOf(config))
        .set(field(name("enabled"), Boolean.class), true)
        .set(field(name("trigger_type"), String.class), "ANOMALY")
        .returning(field(name("id"), Long.class))
        .fetchOne()
        .get(field(name("id"), Long.class));
  }

  private Long insertUploadedFile(Long ownerId, OffsetDateTime expiresAt) {
    String stored = "cleanup-" + UUID.randomUUID();
    Long id =
        dsl.insertInto(UPLOADED_FILES)
            .set(UPLOADED_FILES.ORIGINAL_NAME, stored + ".txt")
            .set(UPLOADED_FILES.STORED_NAME, stored)
            .set(UPLOADED_FILES.MIME_TYPE, "text/plain")
            .set(UPLOADED_FILES.FILE_SIZE, 1L)
            .set(UPLOADED_FILES.FILE_CATEGORY, "TEXT")
            // 존재하지 않는 경로를 쓴다 — Files.deleteIfExists 는 예외를 던지지 않으므로
            // 디스크 파일을 만들지 않고도 DB 삭제 경로를 그대로 검증할 수 있다.
            .set(UPLOADED_FILES.STORAGE_PATH, "/tmp/firehub-test-missing/" + stored)
            .set(UPLOADED_FILES.UPLOADED_BY, ownerId)
            .set(UPLOADED_FILES.EXPIRES_AT, expiresAt)
            .returning(UPLOADED_FILES.ID)
            .fetchOne()
            .getId();
    uploadedFileIds.add(id);
    return id;
  }

  private String insertAsyncJob(Long ownerId, String stage, LocalDateTime timestamp) {
    String id = UUID.randomUUID().toString();
    dsl.insertInto(ASYNC_JOB)
        .set(ASYNC_JOB.ID, id)
        .set(ASYNC_JOB.JOB_TYPE, "TEST_CLEANUP")
        .set(ASYNC_JOB.RESOURCE, "test")
        .set(ASYNC_JOB.RESOURCE_ID, id)
        .set(ASYNC_JOB.USER_ID, ownerId)
        .set(ASYNC_JOB.STAGE, stage)
        .set(ASYNC_JOB.PROGRESS, 10)
        .set(ASYNC_JOB.CREATED_AT, timestamp)
        .set(ASYNC_JOB.UPDATED_AT, timestamp)
        .execute();
    asyncJobIds.add(id);
    return id;
  }

  private Long insertTriggerEvent(Long pipelineId, Long ownerId, LocalDateTime createdAt) {
    Long triggerId =
        dsl.insertInto(PIPELINE_TRIGGER)
            .set(PIPELINE_TRIGGER.PIPELINE_ID, pipelineId)
            .set(PIPELINE_TRIGGER.TRIGGER_TYPE, "API")
            .set(PIPELINE_TRIGGER.NAME, "cleanup-trigger-" + UUID.randomUUID())
            .set(PIPELINE_TRIGGER.IS_ENABLED, false)
            .set(PIPELINE_TRIGGER.CREATED_BY, ownerId)
            .set(PIPELINE_TRIGGER.CREATED_AT, LocalDateTime.now())
            .returning(PIPELINE_TRIGGER.ID)
            .fetchOne()
            .getId();

    return dsl.insertInto(TRIGGER_EVENT)
        .set(TRIGGER_EVENT.PIPELINE_ID, pipelineId)
        .set(TRIGGER_EVENT.TRIGGER_ID, triggerId)
        .set(TRIGGER_EVENT.EVENT_TYPE, "TEST")
        .set(TRIGGER_EVENT.CREATED_AT, createdAt)
        .returning(TRIGGER_EVENT.ID)
        .fetchOne()
        .getId();
  }

  private Long insertExecution(
      Long pipelineId, Long ownerId, LocalDateTime createdAt, String status) {
    return dsl.insertInto(PIPELINE_EXECUTION)
        .set(PIPELINE_EXECUTION.PIPELINE_ID, pipelineId)
        .set(PIPELINE_EXECUTION.STATUS, status)
        .set(PIPELINE_EXECUTION.EXECUTED_BY, ownerId)
        .set(PIPELINE_EXECUTION.CREATED_AT, createdAt)
        .returning(PIPELINE_EXECUTION.ID)
        .fetchOne()
        .getId();
  }

  // ── 검증 조회 ─────────────────────────────────────────────────────────

  private boolean rowExists(long tenantId, Long fileId) {
    return inTenant(
        tenantId, () -> TenantRlsTestSupport.rowExists(dsl, "uploaded_files", "id", fileId));
  }

  private boolean asyncJobExists(long tenantId, String jobId) {
    return inTenant(
        tenantId, () -> dsl.fetchCount(ASYNC_JOB, ASYNC_JOB.ID.eq(jobId)) > 0);
  }

  private String asyncJobStage(long tenantId, String jobId) {
    return inTenant(
        tenantId,
        () ->
            dsl.select(ASYNC_JOB.STAGE)
                .from(ASYNC_JOB)
                .where(ASYNC_JOB.ID.eq(jobId))
                .fetchOne(ASYNC_JOB.STAGE));
  }

  private boolean triggerEventExists(long tenantId, Long eventId) {
    return inTenant(
        tenantId, () -> TenantRlsTestSupport.rowExists(dsl, "trigger_event", "id", eventId));
  }

  private boolean executionExists(long tenantId, Long executionId) {
    return inTenant(
        tenantId, () -> TenantRlsTestSupport.rowExists(dsl, "pipeline_execution", "id", executionId));
  }
}
