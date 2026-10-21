package com.smartfirehub.pipeline.service;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.dataset.storage.DataTableNames;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.dto.CancelExecutionResponse;
import com.smartfirehub.pipeline.dto.CreatePipelineRequest;
import com.smartfirehub.pipeline.dto.ExecutionDetailResponse;
import com.smartfirehub.pipeline.dto.PipelineStepRequest;
import com.smartfirehub.pipeline.dto.PipelineStepResponse;
import com.smartfirehub.pipeline.exception.ExecutionAlreadyFinishedException;
import com.smartfirehub.pipeline.exception.PipelineExecutionCancelledException;
import com.smartfirehub.pipeline.exception.PipelineNotFoundException;
import com.smartfirehub.pipeline.repository.PipelineExecutionRepository;
import com.smartfirehub.pipeline.repository.PipelineStepRepository;
import com.smartfirehub.pipeline.service.executor.SsrfProtectionService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.support.TestUsers;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 파이프라인 실행 취소(WD-153)를 실제 DB·실제 러너로 확인한다.
 *
 * <ul>
 *   <li>실행 중 취소 → 접수(RUNNING) 후 러너가 다음 체크포인트(API 페이지 사이)에서 CANCELLED 로 마감, 남은 스텝은 SKIPPED(취소 사유)
 *   <li>REPLACE 도중 취소 → 원본 데이터 그대로, 이번 실행의 {@code rtmp_} 임시 테이블이 남지 않는다
 *   <li>APPEND 도중 취소 → 넣은 행은 남고 스텝 기록에 실제 행 수(「취소 전까지 N행 적재됨」)
 *   <li>대기 중 취소 → 즉시 CANCELLED, 러너가 나중에 시작해도 아무것도 실행하지 않는다
 *   <li>끝난 실행 → 409, 다른 테넌트·다른 파이프라인 → 404
 * </ul>
 *
 * <p>테스트 트랜잭션은 끈다 — 실행은 {@code @Async} 라 별도 스레드·커넥션에서 돈다(PipelineStepErrorMessageIntegrationTest 와 같은
 * 이유). 외부 API 는 WireMock 이고, SSRF 검사(DNS 로 localhost 차단)는 목으로 우회한다. 실행기는 test 프로필에서 꺼져 있어 로컬
 * ApiCallExecutor 경로(페이지 사이 체크포인트)를 탄다.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PipelineExecutionCancelIntegrationTest extends IntegrationTestBase {

  static WireMockServer wireMock;

  @BeforeAll
  static void startWireMock() {
    wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
    wireMock.start();
  }

  @AfterAll
  static void stopWireMock() {
    wireMock.stop();
  }

  @MockitoBean private SsrfProtectionService ssrfProtectionService;

  @Autowired private DSLContext dsl;
  @Autowired private DatasetService datasetService;
  @Autowired private PipelineService pipelineService;
  @Autowired private PipelineExecutionService executionService;
  @Autowired private PipelineExecutionRepository executionRepository;
  @Autowired private PipelineStepRepository stepRepository;
  @Autowired private PipelineAsyncRunner asyncRunner;

  private String suffix;
  private String outTable;
  private Long outDatasetId;
  private Long pipelineId;
  private Long userId;
  private Long otherTenantId;

  @BeforeEach
  void setUp() {
    wireMock.resetAll();
    suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    outTable = "wd153_out_" + suffix;
    userId = TenantRlsTestSupport.insertUser(dsl, "wd153_user_");
    inTenantFixture(
        () -> TenantRlsTestSupport.insertActiveMembership(dsl, userId, DEFAULT_TEST_TENANT_ID));
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, userId, DEFAULT_TEST_TENANT_ID, "USER");
    outDatasetId =
        datasetService
            .createDataset(
                new CreateDatasetRequest(
                    "WD153 " + outTable,
                    outTable,
                    null,
                    null,
                    "TABLE",
                    "DERIVED",
                    List.of(
                        new DatasetColumnRequest(
                            "name", "name", "TEXT", null, true, false, null, false)),
                    null),
                userId)
            .id();
  }

  @AfterEach
  void tearDown() {
    TenantRlsTestSupport.cleanupAll(
        () -> {
          if (pipelineId != null) pipelineService.deletePipeline(pipelineId);
        },
        () -> datasetService.deleteDataset(outDatasetId),
        () -> {
          if (otherTenantId != null) TenantRlsTestSupport.deleteTenants(dsl, otherTenantId);
        },
        () -> TestUsers.cleanup(dsl, fixtureTransactionTemplate, userId, DEFAULT_TEST_TENANT_ID));
  }

  // ------------------------------------------------------------------ //
  // 실행 중 취소 — API 페이지 사이 체크포인트
  // ------------------------------------------------------------------ //

  /** REPLACE 도중 취소: 원본 2행 그대로, 이번 실행 임시 테이블 없음, 스텝 CANCELLED(원본 유지 문구), 뒤 스텝 SKIPPED(취소 사유). */
  @Test
  void REPLACE_적재_도중_취소하면_원본이_그대로이고_임시_테이블이_남지_않는다() {
    dsl.execute("INSERT INTO " + out() + " (name) VALUES ('orig1'), ('orig2')");
    createApiPipeline("REPLACE");
    stubSlowPages();

    Long executionId = executionService.executePipeline(pipelineId, userId);
    awaitFirstPageServed(executionId);

    CancelExecutionResponse accepted =
        pipelineService.cancelExecution(pipelineId, executionId, userId);
    assertThat(accepted.status()).isEqualTo("RUNNING");
    assertThat(accepted.cancelRequestedAt()).isNotNull();

    ExecutionDetailResponse detail = awaitTerminal(executionId);
    assertThat(detail.status()).isEqualTo("CANCELLED");
    assertThat(detail.cancelRequestedAt()).isNotNull();
    assertThat(detail.cancelRequestedBy()).isNotNull();

    Map<String, StepRow> steps = stepRows(executionId);
    assertThat(steps.get("fetch").status()).isEqualTo("CANCELLED");
    assertThat(steps.get("fetch").error()).contains("기존 데이터를 그대로 유지");
    assertThat(steps.get("after").status()).isEqualTo("SKIPPED");
    assertThat(steps.get("after").error())
        .isEqualTo(PipelineExecutionCancelledException.SKIPPED_BY_CANCEL_MESSAGE);

    // 원본 데이터가 그대로다 — 맞바꾸지 않았다.
    List<String> names = dsl.fetch("SELECT name FROM " + out() + " ORDER BY name").getValues(0, String.class);
    assertThat(names).containsExactly("orig1", "orig2");
    // 이번 실행의 스테이징 임시 테이블(rtmp_<hex>_<md5(출력)>)이 남지 않았다.
    assertThat(leftoverTempTables()).isZero();
    // 남은 페이지는 요청하지 않았다(3페이지째 이후 없음).
    wireMock.verify(0, getRequestedFor(urlPathEqualTo("/items")).withQueryParam("offset", equalTo("4")));
  }

  /** APPEND 도중 취소: 넣은 행은 남고, 스텝 기록의 N 이 실제 출력 행 수와 같다. */
  @Test
  void APPEND_적재_도중_취소하면_넣은_행이_남고_실제_행_수를_기록한다() {
    createApiPipeline("APPEND");
    stubSlowPages();

    Long executionId = executionService.executePipeline(pipelineId, userId);
    awaitFirstPageServed(executionId);
    pipelineService.cancelExecution(pipelineId, executionId, userId);

    ExecutionDetailResponse detail = awaitTerminal(executionId);
    assertThat(detail.status()).isEqualTo("CANCELLED");

    StepRow fetch = stepRows(executionId).get("fetch");
    assertThat(fetch.status()).isEqualTo("CANCELLED");
    int actual = dsl.fetchCount(org.jooq.impl.DSL.table(out()));
    // 첫 페이지(2행)는 반드시, 지연된 둘째 페이지(2행)는 취소 시점에 따라 들어갔다 — 기록된 N 은 실제 행 수와 같아야 한다.
    assertThat(actual).isIn(2, 4);
    Matcher m = Pattern.compile("취소 전까지 (\\d+)행 적재됨").matcher(fetch.error());
    assertThat(m.find()).as("스텝 기록: %s", fetch.error()).isTrue();
    assertThat(Integer.parseInt(m.group(1))).isEqualTo(actual);
    wireMock.verify(0, getRequestedFor(urlPathEqualTo("/items")).withQueryParam("offset", equalTo("4")));
  }

  // ------------------------------------------------------------------ //
  // 대기 중 취소 · 끝난 실행 · 접근 범위
  // ------------------------------------------------------------------ //

  /** 대기 중(PENDING — 스레드 시작 전) 취소: 즉시 CANCELLED, 러너가 나중에 시작해도 스텝을 실행하지 않고 상태를 되살리지 않는다. */
  @Test
  void 대기_중_실행을_취소하면_즉시_CANCELLED_이고_러너가_시작해도_실행하지_않는다() {
    createApiPipeline("APPEND");
    stubSlowPages();
    List<PipelineStepResponse> steps = stepRepository.findByPipelineId(pipelineId);
    // 실행 레코드만 만들고 비동기 위임은 하지 않는다 — 풀 대기열에 있는 실행을 재현한다.
    Long executionId = executionRepository.createExecution(pipelineId, userId, "MANUAL", null);
    Map<Long, Long> stepExecIds = new HashMap<>();
    Map<Long, List<Long>> deps = new HashMap<>();
    for (PipelineStepResponse s : steps) {
      stepExecIds.put(s.id(), executionRepository.createStepExecution(executionId, s.id(), s.name()));
      deps.put(s.id(), List.of());
    }

    CancelExecutionResponse res = pipelineService.cancelExecution(pipelineId, executionId, userId);
    assertThat(res.status()).isEqualTo("CANCELLED");
    assertThat(stepRows(executionId).values()).allMatch(r -> "SKIPPED".equals(r.status()));

    // 대기열에서 스레드가 늦게 시작한 상황 — 프록시를 벗겨 동기로 돌린다.
    PipelineAsyncRunner target = AopTestUtils.getUltimateTargetObject(asyncRunner);
    target.executeAsync(pipelineId, executionId, steps, deps, stepExecIds, userId, false);

    ExecutionDetailResponse detail = pipelineService.getExecutionById(pipelineId, executionId);
    assertThat(detail.status()).isEqualTo("CANCELLED");
    assertThat(detail.startedAt()).isNull();
    assertThat(stepRows(executionId).values()).allMatch(r -> "SKIPPED".equals(r.status()));
    // 외부 API 를 한 번도 부르지 않았다.
    wireMock.verify(0, getRequestedFor(urlPathEqualTo("/items")));
  }

  /** 끝난 실행(COMPLETED·CANCELLED)은 409 — 상태를 바꾸지 않는다. */
  @Test
  void 이미_끝난_실행을_취소하면_409() {
    createApiPipeline("APPEND");
    Long executionId = executionRepository.createExecution(pipelineId, userId, "MANUAL", null);
    inTenantFixture(
        () ->
            dsl.execute(
                "UPDATE pipeline_execution SET status = 'COMPLETED', completed_at = now() WHERE id = ?",
                executionId));

    assertThatThrownBy(() -> pipelineService.cancelExecution(pipelineId, executionId, userId))
        .isInstanceOf(ExecutionAlreadyFinishedException.class)
        .hasMessage("이미 끝난 실행은 취소할 수 없습니다");
    assertThat(pipelineService.getExecutionById(pipelineId, executionId).status())
        .isEqualTo("COMPLETED");
    assertThat(pipelineService.getExecutionById(pipelineId, executionId).cancelRequestedAt())
        .isNull();

    // 대기 중 취소로 끝난 실행을 다시 취소해도 409.
    Long pending = executionRepository.createExecution(pipelineId, userId, "MANUAL", null);
    pipelineService.cancelExecution(pipelineId, pending, userId);
    assertThatThrownBy(() -> pipelineService.cancelExecution(pipelineId, pending, userId))
        .isInstanceOf(ExecutionAlreadyFinishedException.class);
  }

  /** 다른 테넌트에서는 파이프라인이 보이지 않아 404, 다른 파이프라인의 경로로 실행 id 를 넣어도 404(#188) — 상태는 그대로다. */
  @Test
  void 다른_테넌트나_다른_파이프라인에서는_404() {
    createApiPipeline("APPEND");
    Long executionId = executionRepository.createExecution(pipelineId, userId, "MANUAL", null);

    otherTenantId = TenantRlsTestSupport.createActiveTenant(dsl, "wd153");
    Long previous = TenantContext.get();
    TenantContext.set(otherTenantId);
    try {
      assertThatThrownBy(() -> pipelineService.cancelExecution(pipelineId, executionId, userId))
          .isInstanceOf(PipelineNotFoundException.class);
    } finally {
      TenantContext.set(previous);
    }

    // 같은 테넌트의 다른 파이프라인 경로로 이 실행 id 를 넣는다.
    Long otherPipelineId =
        pipelineService
            .createPipeline(
                new CreatePipelineRequest("WD153 other " + suffix, "다른 파이프라인", List.of()), userId)
            .id();
    try {
      assertThatThrownBy(
              () -> pipelineService.cancelExecution(otherPipelineId, executionId, userId))
          .isInstanceOf(PipelineNotFoundException.class);
    } finally {
      pipelineService.deletePipeline(otherPipelineId);
    }
    assertThat(pipelineService.getExecutionById(pipelineId, executionId).status())
        .isEqualTo("PENDING");
  }

  // ------------------------------------------------------------------ //
  // Helpers
  // ------------------------------------------------------------------ //

  private String out() {
    return DataSchema.qualify(outTable);
  }

  /** 첫 페이지는 바로, 둘째 페이지는 1.5초 지연(그 사이 취소가 들어온다), 셋째 페이지·빈 페이지가 이어지는 API. */
  private void stubSlowPages() {
    wireMock.stubFor(
        get(urlPathEqualTo("/items"))
            .withQueryParam("offset", equalTo("0"))
            .willReturn(okJson("{\"items\":[{\"name\":\"p1a\"},{\"name\":\"p1b\"}]}")));
    wireMock.stubFor(
        get(urlPathEqualTo("/items"))
            .withQueryParam("offset", equalTo("2"))
            .willReturn(
                okJson("{\"items\":[{\"name\":\"p2a\"},{\"name\":\"p2b\"}]}")
                    .withFixedDelay(1_500)));
    wireMock.stubFor(
        get(urlPathEqualTo("/items"))
            .withQueryParam("offset", equalTo("4"))
            .willReturn(okJson("{\"items\":[{\"name\":\"p3a\"},{\"name\":\"p3b\"}]}")));
    wireMock.stubFor(
        get(urlPathEqualTo("/items"))
            .withQueryParam("offset", equalTo("6"))
            .willReturn(okJson("{\"items\":[]}")));
  }

  /** API_CALL(fetch, 페이지 2행) → SQL(after, fetch 에 의존) 두 스텝 파이프라인. */
  private void createApiPipeline(String loadStrategy) {
    Map<String, Object> apiConfig = new LinkedHashMap<>();
    apiConfig.put("customUrl", "http://localhost:" + wireMock.port() + "/items");
    apiConfig.put("method", "GET");
    apiConfig.put("dataPath", "$.items");
    apiConfig.put(
        "fieldMappings",
        List.of(Map.of("sourceField", "name", "targetColumn", "name", "dataType", "TEXT")));
    apiConfig.put(
        "pagination",
        Map.of("type", "OFFSET", "pageSize", 2, "offsetParam", "offset", "limitParam", "limit"));
    apiConfig.put("retry", Map.of("maxRetries", 0, "initialBackoffMs", 100, "maxBackoffMs", 100));
    apiConfig.put("timeoutMs", 10_000);
    PipelineStepRequest fetch =
        new PipelineStepRequest(
            "fetch",
            "외부 API 적재",
            "API_CALL",
            null,
            outDatasetId,
            null,
            null,
            loadStrategy,
            apiConfig,
            null,
            null,
            null);
    PipelineStepRequest after =
        new PipelineStepRequest(
            "after",
            "후속 스텝",
            "SQL",
            "SELECT name FROM " + out(),
            null,
            null,
            List.of("fetch"),
            "REPLACE");
    pipelineId =
        pipelineService
            .createPipeline(
                new CreatePipelineRequest(
                    "WD153 Pipeline " + suffix, "실행 취소 통합 테스트", List.of(fetch, after)),
                userId)
            .id();
  }

  /** 러너가 첫 페이지를 받아 간 뒤(둘째 페이지는 지연 중)까지 기다린다. */
  private void awaitFirstPageServed(Long executionId) {
    long deadline = System.currentTimeMillis() + 30_000;
    while (System.currentTimeMillis() < deadline) {
      if (!wireMock.findAll(getRequestedFor(urlPathEqualTo("/items"))).isEmpty()) {
        return;
      }
      sleep(50);
    }
    throw new IllegalStateException("API 첫 페이지 요청이 오지 않았다: execution " + executionId);
  }

  /** 종료 상태(COMPLETED/FAILED/CANCELLED)까지 기다린다. */
  private ExecutionDetailResponse awaitTerminal(Long executionId) {
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

  /** 출력 테이블의 이번 실행 스테이징 임시 테이블 개수(이름이 rtmp_<hex>_<md5(출력)>). */
  private int leftoverTempTables() {
    String md5 = DataTableNames.md5Hex(outTable);
    return dsl.fetchCount(
        dsl.selectOne()
            .from("information_schema.tables")
            .where("table_name LIKE 'rtmp\\_%' AND table_name LIKE ?", "%" + md5));
  }

  private record StepRow(String status, String error) {}

  /** 스텝 이름별 저장본(조회 API 의 가림 판정을 거치지 않고 직접 읽는다). */
  private Map<String, StepRow> stepRows(Long executionId) {
    List<Record> rows =
        inTenantFixture(
            () ->
                dsl.fetch(
                    "SELECT step_name, status, error_message FROM pipeline_step_execution"
                        + " WHERE execution_id = ?",
                    executionId));
    Map<String, StepRow> byName = new HashMap<>();
    for (Record r : rows) {
      byName.put(
          r.get(0, String.class), new StepRow(r.get(1, String.class), r.get(2, String.class)));
    }
    return byName;
  }
}
