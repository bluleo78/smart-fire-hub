package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.dto.CreatePipelineRequest;
import com.smartfirehub.pipeline.dto.ExecutionDetailResponse;
import com.smartfirehub.pipeline.dto.PipelineStepRequest;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.support.TestUsers;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 워크스페이스별 동시 실행 관문(WD-439)을 실제 DB·실제 러너로 확인한다.
 *
 * <ul>
 *   <li>상한(여기서는 1)을 넘는 실행은 실패하지 않고 PENDING 으로 기다리다가 앞 실행이 끝나면 시작해 완료된다
 *   <li>기다리던 실행을 취소하면 곧바로 CANCELLED 이고 대기열에서 빠지며, 자리를 막지 않아 다음 실행이 돈다
 *   <li>대기 한도를 넘긴 실행은 한국어 안내로 FAILED 가 된다(내부 풀 이름·영어 원문 없음)
 * </ul>
 *
 * <p>test 프로필은 관문을 끄므로(기존 테스트 동작 유지) 이 클래스만 상한 1·짧은 대기 한도로 켠다. 테스트 트랜잭션은 끈다 — 실행은 {@code @Async} 라
 * 별도 스레드·커넥션에서 돈다(PipelineExecutionCancelIntegrationTest 와 같은 이유).
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(
    properties = {
      "app.pipeline.tenant-concurrency.max-running=1",
      "app.pipeline.tenant-concurrency.max-wait=PT4S",
      "app.pipeline.tenant-concurrency.check-interval=PT1S"
    })
class PipelineTenantAdmissionIntegrationTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private PipelineService pipelineService;
  @Autowired private PipelineExecutionService executionService;
  @Autowired private PipelineTenantAdmission tenantAdmission;
  @Autowired private PipelineExecutionRegistry executionRegistry;
  @Autowired private DatasetService datasetService;

  private String suffix;
  private Long userId;
  private final List<Long> pipelineIds = new ArrayList<>();

  /** 앞 실행을 붙잡는 관문 테이블 — 테스트가 ACCESS EXCLUSIVE 잠금을 쥔 동안 이 테이블을 읽는 SQL 스텝은 기다린다. */
  private String gateTable;

  /** 잠금이 필요 없는 테이블 — 뒤 실행(빨리 끝나는 실행)이 읽는다. */
  private String freeTable;

  private final List<Long> datasetIds = new ArrayList<>();
  private ExecutorService lockHolder;
  private CountDownLatch releaseGate;
  private Future<?> gateHold;

  @BeforeEach
  void setUp() {
    suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    userId = TenantRlsTestSupport.insertUser(dsl, "wd439_user_");
    inTenantFixture(
        () -> TenantRlsTestSupport.insertActiveMembership(dsl, userId, DEFAULT_TEST_TENANT_ID));
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, userId, DEFAULT_TEST_TENANT_ID, "USER");
    gateTable = "wd439_gate_" + suffix;
    freeTable = "wd439_free_" + suffix;
    // 파이프라인 SQL 은 데이터셋으로 등록된 테이블만 읽을 수 있다(보안 등급 판정) — 데이터셋으로 만든다.
    for (String table : List.of(gateTable, freeTable)) {
      datasetIds.add(
          datasetService
              .createDataset(
                  new CreateDatasetRequest(
                      "WD439 " + table,
                      table,
                      null,
                      null,
                      "TABLE",
                      "DERIVED",
                      List.of(
                          new DatasetColumnRequest(
                              "name", "name", "TEXT", null, true, false, null, false)),
                      null),
                  userId)
              .id());
    }
    lockHolder = Executors.newSingleThreadExecutor();
  }

  @AfterEach
  void tearDown() {
    openGate();
    lockHolder.shutdownNow();
    List<Runnable> cleanups = new ArrayList<>();
    for (Long id : pipelineIds) {
      cleanups.add(() -> pipelineService.deletePipelineUnchecked(id));
    }
    for (Long id : datasetIds) {
      cleanups.add(() -> datasetService.deleteDataset(id));
    }
    cleanups.add(
        () -> TestUsers.cleanup(dsl, fixtureTransactionTemplate, userId, DEFAULT_TEST_TENANT_ID));
    TenantRlsTestSupport.cleanupAll(cleanups.toArray(Runnable[]::new));
  }

  /** 상한을 넘는 실행은 실패하지 않고 PENDING 으로 기다렸다가, 앞 실행이 끝나면 시작해 완료된다. */
  @Test
  void executionBeyondLimit_waitsPendingThenCompletes() {
    closeGate();
    Long slow = readingPipeline("slow", gateTable);
    Long quick = readingPipeline("quick", freeTable);

    Long first = executionService.executePipeline(slow, userId);
    awaitStatus(slow, first, "RUNNING");
    Long second = executionService.executePipeline(quick, userId);

    // 앞 실행이 도는 동안 둘째는 러너에 넘어가지 않고 PENDING 그대로 대기열에 있다.
    sleep(500);
    assertThat(pipelineService.getExecutionById(quick, second).status()).isEqualTo("PENDING");
    assertThat(tenantAdmission.isWaiting(second)).isTrue();
    // 대기 중에도 등록부에 남아 하트비트를 받는다 — 정리기가 「중단됨」으로 닫지 않는다.
    assertThat(executionRegistry.isRegistered(second)).isTrue();

    openGate();
    assertThat(awaitTerminal(slow, first).status()).isEqualTo("COMPLETED");
    assertThat(awaitTerminal(quick, second).status()).isEqualTo("COMPLETED");
    assertThat(tenantAdmission.isWaiting(second)).isFalse();
    assertThat(executionRegistry.isRegistered(second)).isFalse();
  }

  /** 기다리던 실행을 취소하면 곧바로 CANCELLED 이고 대기열·등록부에서 빠져, 그 뒤 실행이 자리를 받는다. */
  @Test
  void cancellingWaitingExecution_removesItFromQueue() {
    closeGate();
    Long slow = readingPipeline("slow", gateTable);
    Long quick = readingPipeline("quick", freeTable);

    Long first = executionService.executePipeline(slow, userId);
    awaitStatus(slow, first, "RUNNING");
    Long waiting = executionService.executePipeline(quick, userId);
    Long next = executionService.executePipeline(quick, userId);

    assertThat(executionService.cancelExecution(quick, waiting, userId).status())
        .isEqualTo("CANCELLED");
    assertThat(tenantAdmission.isWaiting(waiting)).isFalse();
    assertThat(executionRegistry.isRegistered(waiting)).isFalse();

    openGate();
    assertThat(awaitTerminal(slow, first).status()).isEqualTo("COMPLETED");
    assertThat(awaitTerminal(quick, next).status()).isEqualTo("COMPLETED");
    assertThat(pipelineService.getExecutionById(quick, waiting).status()).isEqualTo("CANCELLED");
  }

  /** 대기 한도(여기서는 4초)를 넘긴 실행은 한국어 안내로 FAILED 가 되고, 스텝은 실행하지 않은 채 닫힌다. */
  @Test
  void executionWaitingPastLimit_failsWithKoreanMessage() {
    closeGate();
    Long slow = readingPipeline("slow", gateTable);
    Long quick = readingPipeline("quick", freeTable);

    Long first = executionService.executePipeline(slow, userId);
    awaitStatus(slow, first, "RUNNING");
    Long starved = executionService.executePipeline(quick, userId);
    try {
      ExecutionDetailResponse detail = awaitTerminal(quick, starved);

      assertThat(detail.status()).isEqualTo("FAILED");
      // 조회 응답은 실행 주체가 아닌 열람자에게 문구를 가리므로(WD-27) 저장된 문구를 직접 본다(RLS 라 테넌트 트랜잭션 안에서).
      String stored =
          inTenantFixture(
              () ->
                  dsl.fetchOne("SELECT error_message FROM pipeline_execution WHERE id = ?", starved)
                      .get(0, String.class));
      assertThat(stored)
          .isEqualTo(PipelineTenantAdmission.WAIT_TIMEOUT_MESSAGE)
          .doesNotContain("pipeline-tenant")
          .doesNotContain("Connection is not available");
      assertThat(detail.stepExecutions())
          .allSatisfy(step -> assertThat(step.status()).isEqualTo("SKIPPED"));
      assertThat(tenantAdmission.isWaiting(starved)).isFalse();
      assertThat(executionRegistry.isRegistered(starved)).isFalse();
    } finally {
      openGate();
      awaitTerminal(slow, first);
    }
  }

  /** {@code table} 을 읽는 SQL 스텝 하나짜리 파이프라인을 만든다(출력은 자동 임시 출력). */
  private Long readingPipeline(String name, String table) {
    PipelineStepRequest step =
        new PipelineStepRequest(
            "read",
            "테이블 읽기",
            "SQL",
            "SELECT name FROM " + DataSchema.qualify(table),
            null,
            null,
            List.of(),
            "REPLACE");
    Long id =
        pipelineService
            .createPipeline(
                new CreatePipelineRequest(
                    "WD439 " + name + " " + suffix, "동시 실행 관문 통합 테스트", List.of(step)),
                userId)
            .id();
    pipelineIds.add(id);
    return id;
  }

  /**
   * 관문 테이블에 ACCESS EXCLUSIVE 잠금을 건 트랜잭션을 다른 스레드에서 열어 둔다 — {@link #openGate()} 까지 그 테이블을 읽는 실행은 SQL
   * 스텝에서 기다린다(RUNNING 그대로). 잠금이 실제로 잡힌 뒤 돌아온다.
   */
  private void closeGate() {
    releaseGate = new CountDownLatch(1);
    CountDownLatch locked = new CountDownLatch(1);
    gateHold =
        lockHolder.submit(
            () ->
                TenantContext.runScoped(
                    DEFAULT_TEST_TENANT_ID,
                    () ->
                        dsl.transaction(
                            cfg -> {
                              cfg.dsl()
                                  .execute(
                                      "LOCK TABLE "
                                          + DataSchema.qualify(gateTable)
                                          + " IN ACCESS EXCLUSIVE MODE");
                              locked.countDown();
                              releaseGate.await(60, TimeUnit.SECONDS);
                            })));
    try {
      assertThat(locked.await(20, TimeUnit.SECONDS)).as("관문 잠금을 잡아야 한다").isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** 관문 잠금 트랜잭션을 끝낸다(이미 열었거나 닫은 적 없으면 아무것도 안 한다). */
  private void openGate() {
    if (releaseGate == null) {
      return;
    }
    releaseGate.countDown();
    try {
      gateHold.get(20, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException("관문 잠금 트랜잭션이 끝나지 않았다", e);
    }
    releaseGate = null;
  }

  private void awaitStatus(Long pipelineId, Long executionId, String status) {
    long deadline = System.currentTimeMillis() + 30_000;
    while (System.currentTimeMillis() < deadline) {
      if (status.equals(pipelineService.getExecutionById(pipelineId, executionId).status())) {
        return;
      }
      sleep(50);
    }
    throw new IllegalStateException("실행이 " + status + " 가 되지 않았다: " + executionId);
  }

  private ExecutionDetailResponse awaitTerminal(Long pipelineId, Long executionId) {
    long deadline = System.currentTimeMillis() + 60_000;
    ExecutionDetailResponse detail = null;
    while (System.currentTimeMillis() < deadline) {
      detail = pipelineService.getExecutionById(pipelineId, executionId);
      if (List.of("COMPLETED", "FAILED", "CANCELLED").contains(detail.status())) {
        return detail;
      }
      sleep(100);
    }
    throw new IllegalStateException("실행이 끝나지 않았다: " + detail);
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
