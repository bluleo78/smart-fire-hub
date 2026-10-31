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
 * 워크스페이스별 동시 실행 관문(WD-439) 통합 테스트의 공통 기반 — 관문 테이블 잠금으로 앞 실행을 붙잡는 픽스처와 대기 헬퍼를 둔다.
 *
 * <p><b>왜 대기 한도(max-wait)를 여기서 정하지 않나(WD-478).</b> 「기다렸다 완료」를 보는 테스트는 한도가 넉넉해야 하고(부하 중 앞 실행이 한도 안에
 * 끝나야 한다), 「한도 초과」를 보는 테스트는 한도가 짧아야 한다. 한 클래스에 한도 하나만 걸 수 있어 짧은 한도(4초)가 앞 테스트를 부하 중 가끔
 * FAILED 로 닫았다 — 그래서 한도는 하위 클래스가 각자 {@code @TestPropertySource} 로 정한다.
 *
 * <p>test 프로필은 관문을 끄므로(기존 테스트 동작 유지) 하위 클래스가 상한 1로 켠다. 테스트 트랜잭션은 끈다 — 실행은 {@code @Async} 라 별도
 * 스레드·커넥션에서 돈다(PipelineExecutionCancelIntegrationTest 와 같은 이유).
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(
    properties = {
      "app.pipeline.tenant-concurrency.max-running=1",
      "app.pipeline.tenant-concurrency.check-interval=PT1S"
    })
abstract class AbstractPipelineTenantAdmissionIntegrationTest extends IntegrationTestBase {

  @Autowired protected DSLContext dsl;
  @Autowired protected PipelineService pipelineService;
  @Autowired protected PipelineExecutionService executionService;
  @Autowired protected PipelineTenantAdmission tenantAdmission;
  @Autowired protected PipelineExecutionRegistry executionRegistry;
  @Autowired protected DatasetService datasetService;

  protected String suffix;
  protected Long userId;
  protected final List<Long> pipelineIds = new ArrayList<>();

  /** 앞 실행을 붙잡는 관문 테이블 — 테스트가 ACCESS EXCLUSIVE 잠금을 쥔 동안 이 테이블을 읽는 SQL 스텝은 기다린다. */
  protected String gateTable;

  /** 잠금이 필요 없는 테이블 — 뒤 실행(빨리 끝나는 실행)이 읽는다. */
  protected String freeTable;

  protected final List<Long> datasetIds = new ArrayList<>();
  protected ExecutorService lockHolder;
  protected CountDownLatch releaseGate;
  protected Future<?> gateHold;

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

  /** {@code table} 을 읽는 SQL 스텝 하나짜리 파이프라인을 만든다(출력은 자동 임시 출력). */
  protected Long readingPipeline(String name, String table) {
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
  protected void closeGate() {
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
  protected void openGate() {
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

  protected void awaitStatus(Long pipelineId, Long executionId, String status) {
    long deadline = System.currentTimeMillis() + 30_000;
    while (System.currentTimeMillis() < deadline) {
      if (status.equals(pipelineService.getExecutionById(pipelineId, executionId).status())) {
        return;
      }
      sleep(50);
    }
    throw new IllegalStateException("실행이 " + status + " 가 되지 않았다: " + executionId);
  }

  protected ExecutionDetailResponse awaitTerminal(Long pipelineId, Long executionId) {
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

  /**
   * 실행이 최종 상태가 된 <b>뒤에</b> 대기열·등록부에서 빠지는 것을 기다린다(WD-478) — 러너·대기 한도 마감은 상태를 먼저 커밋하고 등록부를 {@code finally}
   * 에서 비우므로, 최종 상태를 본 직후 곧바로 단언하면 부하 중 그 사이 틈에 걸린다.
   */
  protected void awaitReleased(Long executionId) {
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline) {
      if (!tenantAdmission.isWaiting(executionId) && !executionRegistry.isRegistered(executionId)) {
        return;
      }
      sleep(50);
    }
    assertThat(tenantAdmission.isWaiting(executionId)).as("대기열에서 빠져야 한다").isFalse();
    assertThat(executionRegistry.isRegistered(executionId)).as("등록부에서 빠져야 한다").isFalse();
  }

  protected static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
