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
import com.smartfirehub.pipeline.service.executor.ExecutionCancelCheck;
import com.smartfirehub.pipeline.service.executor.SsrfProtectionService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.support.TestUsers;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
 * <p>테스트 트랜잭션은 끈다 — 실행은 {@code @Async} 라 별도 스레드·커넥션에서 돈다(PipelineStepErrorMessageIntegrationTest 와
 * 같은 이유). 외부 API 는 WireMock 이고, SSRF 검사(DNS 로 localhost 차단)는 목으로 우회한다. 실행기는 test 프로필에서 꺼져 있어 로컬
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

  /**
   * 문장 실행 시작 신호용 스파이(WD-479) — 러너는 「SQL 실행 직전」 취소 체크포인트를 지난 뒤에야 {@code execute} 를 부른다. 그래서 이 호출이 곧
   * 「체크포인트를 지나 문장이 돌기 시작했다」는 결정적 신호다(시간으로 기다리지 않는다). 호출은 그대로 실제 구현에 위임한다.
   */
  @MockitoSpyBean private SqlScriptExecutor sqlScriptExecutor;

  @Autowired private DSLContext dsl;
  @Autowired private DataSource dataSource;

  @LocalServerPort private int port;

  @Value("${app.executor.internal-token}")
  private String executorToken;

  /** db-executor 내부 토큰 — Tibero 경로의 잠금 뒤 취소 확인도 같은 엔드포인트를 부른다(WD-482). */
  @Value("${app.db-executor.internal-token}")
  private String dbExecutorToken;

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
          if (pipelineId != null) pipelineService.deletePipelineUnchecked(pipelineId);
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
    List<String> names =
        dsl.fetch("SELECT name FROM " + out() + " ORDER BY name").getValues(0, String.class);
    assertThat(names).containsExactly("orig1", "orig2");
    // 이번 실행의 스테이징 임시 테이블(rtmp_<hex>_<md5(출력)>)이 남지 않았다.
    assertThat(leftoverTempTables()).isZero();
    // 남은 페이지는 요청하지 않았다(3페이지째 이후 없음).
    wireMock.verify(
        0, getRequestedFor(urlPathEqualTo("/items")).withQueryParam("offset", equalTo("4")));
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
    wireMock.verify(
        0, getRequestedFor(urlPathEqualTo("/items")).withQueryParam("offset", equalTo("4")));
  }

  /**
   * 마지막 스텝이 문장을 실행하는 중에 취소(WD-411): 그 뒤로 체크포인트가 없어 예전엔 실행이 COMPLETED 로 끝나 연쇄 트리거가 하위를 돌렸다. 이미 시작한
   * 문장의 결과는 그대로 두고(스텝 COMPLETED), 실행은 CANCELLED 로 마감해야 한다 — 연쇄·완료 알림은 CANCELLED 로는 발화하지 않는다.
   */
  @Test
  void 마지막_스텝_실행_중에_취소하면_스텝은_완료여도_실행은_CANCELLED_로_끝난다() {
    PipelineStepRequest slow =
        new PipelineStepRequest(
            // 몇 초 걸리는 문장(pg_sleep 은 SQL 검증이 막는다 — 이슈 재현과 같은 generate_series).
            "slow",
            "느린 마지막 스텝",
            "SQL",
            "SELECT count(*)::text AS name FROM generate_series(1, 80000000) g",
            null,
            null,
            List.of(),
            "REPLACE");
    pipelineId =
        pipelineService
            .createPipeline(
                new CreatePipelineRequest("WD411 Pipeline " + suffix, "마지막 스텝 취소", List.of(slow)),
                userId)
            .id();

    // 문장 실행이 시작되는 순간(체크포인트 통과 뒤)을 잡는 래치 — 고정 시간 대기는 부하가 크면 체크포인트 통과 전에 취소해 스텝까지 CANCELLED 가 됐다.
    // WD-466 부터 REPLACE 는 출력 잠금을 얻은 직후에도 취소를 확인하므로, 그 확인까지 지난 뒤에 래치를 연다(아니면 그 확인이 취소를 잡는다).
    CountDownLatch statementStarted = new CountDownLatch(1);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              if (invocation.getArgument(2) instanceof LatchingCancel) {
                return invocation.callRealMethod();
              }
              List<String> pre = invocation.getArgument(0);
              LatchingCancel wrapped =
                  new LatchingCancel(invocation.getArgument(2), statementStarted);
              // 선행 문장이 없으면 잠금 뒤 확인도 없다 — 바로 연다.
              if (pre.isEmpty()) {
                statementStarted.countDown();
              }
              return ((SqlScriptExecutor) invocation.getMock())
                  .execute(pre, invocation.getArgument(1), wrapped);
            })
        .when(sqlScriptExecutor)
        .execute(
            org.mockito.ArgumentMatchers.anyList(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any());

    Long executionId = executionService.executePipeline(pipelineId, userId);
    // 「SQL 실행 직전」 체크포인트를 지나 문장이 돌기 시작한 뒤에 취소한다 — 그 전이면 체크포인트가 잡아 스텝도 CANCELLED 다. 문장은 수 초 걸려
    // 신호 직후 취소해도 아직 도는 중이다.
    awaitStepRunning(executionId, "slow");
    assertThat(awaitLatch(statementStarted)).as("문장 실행 시작 신호").isTrue();
    CancelExecutionResponse accepted =
        pipelineService.cancelExecution(pipelineId, executionId, userId);
    assertThat(accepted.status()).isEqualTo("RUNNING");

    ExecutionDetailResponse detail = awaitTerminal(executionId);
    assertThat(detail.status()).isEqualTo("CANCELLED");
    assertThat(detail.cancelRequestedAt()).isNotNull();
    // 이미 시작한 문장은 끝까지 돌았다 — 스텝 기록은 그대로 완료다(확인 창 안내와 같다).
    assertThat(stepRows(executionId).get("slow").status()).isEqualTo("COMPLETED");
  }

  /**
   * 출력 잠금을 기다리는 동안 취소(WD-466): 같은 출력을 쓰는 앞 실행(여기서는 잠금을 쥔 연결로 흉내 낸다)이 끝나기를 기다리는 사이 들어온 취소는 러너의 「SQL
   * 실행 직전」 체크포인트보다 뒤라 보이지 않았고, 잠금을 얻은 뒤 사용자 SQL 이 끝까지 돌아 출력을 다시 교체했다. 잠금을 얻은 직후 취소를 확인해 스텝·실행을
   * CANCELLED 로 마감하고 출력은 앞 실행의 결과 그대로여야 한다.
   */
  @Test
  void 출력_잠금_대기_중에_취소하면_잠금을_얻은_뒤_SQL_을_돌리지_않고_출력이_그대로다() throws Exception {
    dsl.execute("INSERT INTO " + out() + " (name) VALUES ('from_a1'), ('from_a2')");
    PipelineStepRequest replace =
        new PipelineStepRequest(
            "replace",
            "출력 교체",
            "SQL",
            "SELECT 'from_b'::text AS name",
            outDatasetId,
            null,
            List.of(),
            "REPLACE");
    pipelineId =
        pipelineService
            .createPipeline(
                new CreatePipelineRequest(
                    "WD466 Pipeline " + suffix, "잠금 대기 중 취소", List.of(replace)),
                userId)
            .id();

    // 앞 실행 A 흉내 — 같은 키의 트랜잭션 범위 출력 잠금을 쥐고 놓지 않는다(러너·실행기와 같은 키 식).
    String lockKey = SqlScriptExecutor.outputLockKey(OutputClearStatement.deleteAll(outTable));
    try (Connection holder = dataSource.getConnection()) {
      holder.setAutoCommit(false);
      try (PreparedStatement ps = holder.prepareStatement(SqlScriptExecutor.OUTPUT_LOCK_SQL)) {
        ps.setString(1, lockKey);
        ps.execute();
      }
      try {
        Long executionId = executionService.executePipeline(pipelineId, userId);
        // 실행 B 가 출력 잠금을 기다리기 시작할 때까지 — 체크포인트를 지나 잠금 문장에서 막혔다.
        awaitAdvisoryLockWaiter();

        CancelExecutionResponse accepted =
            pipelineService.cancelExecution(pipelineId, executionId, userId);
        assertThat(accepted.status()).isEqualTo("RUNNING");

        // 앞 실행이 끝난다(잠금 해제) — B 가 잠금을 얻는다.
        holder.rollback();

        ExecutionDetailResponse detail = awaitTerminal(executionId);
        assertThat(detail.status()).isEqualTo("CANCELLED");
        StepRow step = stepRows(executionId).get("replace");
        assertThat(step.status()).isEqualTo("CANCELLED");
        assertThat(step.error()).contains("출력 데이터는 바뀌지 않았습니다");
      } finally {
        holder.rollback();
      }
    }
    // B 의 SQL 은 돌지 않았다 — 출력은 앞 실행의 결과 그대로다.
    List<String> names =
        dsl.fetch("SELECT name FROM " + out() + " ORDER BY name").getValues(0, String.class);
    assertThat(names).containsExactly("from_a1", "from_a2");
  }

  /**
   * 실행기 되묻기 엔드포인트(WD-466): Python 실행기가 출력 잠금을 얻은 뒤 사용자 SQL 전에 부른다. 실행기 토큰으로만 답하고, 취소 요청 전후의 판정이 러너와
   * 같으며, 다른 테넌트로 물으면 실행이 보이지 않아 「중지」로 답한다(출력을 건드리지 않는 쪽).
   */
  @Test
  void 실행기_중지_확인_엔드포인트는_취소_요청을_그대로_답한다() throws Exception {
    createApiPipeline("APPEND");
    Long executionId = executionRepository.createExecution(pipelineId, userId, "MANUAL", null);
    inTenantFixture(
        () ->
            dsl.execute(
                "UPDATE pipeline_execution SET status = 'RUNNING', started_at = now() WHERE id = ?",
                executionId));

    String auth = "Internal " + executorToken;
    assertThat(getStopRequested(DEFAULT_TEST_TENANT_ID, executionId, "Internal wrong").statusCode())
        .isEqualTo(401);
    HttpResponse<String> before = getStopRequested(DEFAULT_TEST_TENANT_ID, executionId, auth);
    assertThat(before.statusCode()).isEqualTo(200);
    assertThat(before.body()).contains("\"stopRequested\":false");

    // db-executor 토큰도 같은 판정을 받는다(WD-482 — Tibero 경로). 빈 토큰 헤더는 어느 쪽으로도 통과하지 않는다.
    HttpResponse<String> viaDbExecutor =
        getStopRequested(DEFAULT_TEST_TENANT_ID, executionId, "Internal " + dbExecutorToken);
    assertThat(viaDbExecutor.statusCode()).isEqualTo(200);
    assertThat(viaDbExecutor.body()).contains("\"stopRequested\":false");
    assertThat(getStopRequested(DEFAULT_TEST_TENANT_ID, executionId, "Internal ").statusCode())
        .isEqualTo(401);

    pipelineService.cancelExecution(pipelineId, executionId, userId);
    assertThat(getStopRequested(DEFAULT_TEST_TENANT_ID, executionId, auth).body())
        .contains("\"stopRequested\":true");
    assertThat(
            getStopRequested(DEFAULT_TEST_TENANT_ID, executionId, "Internal " + dbExecutorToken)
                .body())
        .contains("\"stopRequested\":true");

    // 다른 테넌트로 물으면 RLS 로 실행이 보이지 않는다 — 판정 규칙상 중지.
    otherTenantId = TenantRlsTestSupport.createActiveTenant(dsl, "wd466");
    Long fresh = executionRepository.createExecution(pipelineId, userId, "MANUAL", null);
    assertThat(getStopRequested(DEFAULT_TEST_TENANT_ID, fresh, auth).body())
        .contains("\"stopRequested\":false");
    assertThat(getStopRequested(otherTenantId, fresh, auth).body())
        .contains("\"stopRequested\":true");
  }

  private HttpResponse<String> getStopRequested(long tenantId, long executionId, String auth)
      throws Exception {
    HttpRequest req =
        HttpRequest.newBuilder(
                URI.create(
                    "http://localhost:"
                        + port
                        + "/internal/executor/tenants/"
                        + tenantId
                        + "/pipeline-executions/"
                        + executionId
                        + "/stop-requested"))
            .header("Authorization", auth)
            .GET()
            .build();
    return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
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
      stepExecIds.put(
          s.id(), executionRepository.createStepExecution(executionId, s.id(), s.name()));
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
      // WD-226: 다른 파이프라인 경로의 실행 취소는 내부 id 없는 한국어 문구로 404
      assertThatThrownBy(
              () -> pipelineService.cancelExecution(otherPipelineId, executionId, userId))
          .isInstanceOf(PipelineNotFoundException.class)
          .hasMessage("실행 기록을 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.");
    } finally {
      pipelineService.deletePipelineUnchecked(otherPipelineId);
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

  /** 이름이 {@code stepName} 인 스텝이 RUNNING 이 될 때까지 기다린다. */
  private void awaitStepRunning(Long executionId, String stepName) {
    long deadline = System.currentTimeMillis() + 30_000;
    while (System.currentTimeMillis() < deadline) {
      StepRow row = stepRows(executionId).get(stepName);
      if (row != null && "RUNNING".equals(row.status())) {
        return;
      }
      sleep(50);
    }
    throw new IllegalStateException("스텝이 시작되지 않았다: " + stepName);
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

  /** 부여되지 않은(기다리는) advisory 잠금 요청이 생길 때까지 기다린다(WD-466 — 실행이 출력 잠금에서 막혔다는 신호). */
  private void awaitAdvisoryLockWaiter() {
    long deadline = System.currentTimeMillis() + 30_000;
    while (System.currentTimeMillis() < deadline) {
      if (dsl.fetchCount(
              dsl.selectOne().from("pg_locks").where("locktype = 'advisory' AND NOT granted"))
          > 0) {
        return;
      }
      sleep(50);
    }
    throw new IllegalStateException("출력 잠금을 기다리는 실행이 없다");
  }

  /**
   * 잠금 뒤 취소 확인이 지나간 순간을 알리는 취소 확인(WD-411 테스트용) — 실제 판정에 위임하고, 확인 결과가 「취소 아님」이면 래치를 연다. 그 뒤에 취소해야 이미
   * 시작한 문장의 결과가 남는 경우를 재현한다.
   */
  private record LatchingCancel(ExecutionCancelCheck delegate, CountDownLatch latch)
      implements ExecutionCancelCheck {
    @Override
    public boolean isCancelRequested() {
      boolean requested = delegate.isCancelRequested();
      if (!requested) {
        latch.countDown();
      }
      return requested;
    }
  }

  /** 래치가 열릴 때까지 최대 60초 기다린다 — 부하가 커도 문장이 시작은 한다. */
  private static boolean awaitLatch(CountDownLatch latch) {
    try {
      return latch.await(60, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
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
