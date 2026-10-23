package com.smartfirehub.proactive.service;

import static com.smartfirehub.jooq.Tables.PROACTIVE_JOB;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantScopedRunner;
import com.smartfirehub.pipeline.service.PipelineDataSchema;
import com.smartfirehub.pipeline.service.executor.ExecutorClient.QueryExecuteResult;
import com.smartfirehub.pipeline.service.validator.SqlValidator;
import com.smartfirehub.proactive.repository.MetricSnapshotRepository;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import com.smartfirehub.securitylevel.ai.AiCallContext;
import com.smartfirehub.securitylevel.ai.AiHostingResolver;
import com.smartfirehub.securitylevel.sql.GuardedSqlExecutor;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 지표 폴러의 데이터셋 메트릭 사전 검증이 테넌트 저장소를 따르는지 고정한다(WD-76).
 *
 * <p>예전에는 PG 애드혹 검증기(data_t{id} 허용목록)로만 사전 검증해 Tibero 테넌트의 {@code genia_t5.orders} 메트릭이 관문에 닿기 전에
 * "invalid query" 로 건너뛰어져 한 번도 수집되지 않았다. 스프링 컨텍스트 없이 협력자를 목으로 꽂아 폴링 한 주기를 그대로 돌린다 — 잡 조회(트랜잭션
 * 템플릿)·테넌트 순회·AI 문맥은 콜백을 그대로 실행하는 목이다.
 */
class MetricPollerServiceTiberoTest {

  private static final long TENANT_ID = 5L;
  private static final long JOB_ID = 1L;

  private final GuardedSqlExecutor gate = mock(GuardedSqlExecutor.class);
  private final PipelineDataSchema pipelineDataSchema = mock(PipelineDataSchema.class);
  private final MetricSnapshotRepository snapshots = mock(MetricSnapshotRepository.class);
  private final TenantScopedRunner runner = mock(TenantScopedRunner.class);
  private final TransactionTemplate tx = mock(TransactionTemplate.class);
  private final AiCallContext aiCallContext = mock(AiCallContext.class);
  private MetricPollerService poller;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    // 테넌트 순회: 실제 러너처럼 콜백 동안 테넌트 컨텍스트를 세운다(폴러가 isTibero·DataSchema 를 이 컨텍스트로 해석한다).
    doAnswer(
            inv -> {
              TenantContext.runScoped(
                  TENANT_ID, () -> ((Consumer<Long>) inv.getArgument(0)).accept(TENANT_ID));
              return null;
            })
        .when(runner)
        .forEachActiveTenant(any());
    // 공유 목적 AI 문맥은 작업을 그대로 실행한다.
    when(aiCallContext.callWith(any(), any()))
        .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
    when(pipelineDataSchema.validator()).thenReturn(SqlValidator.forTiberoSchema("GENIA_T5"));
    when(gate.executeMetricQuery(any(), any()))
        .thenReturn(
            new QueryExecuteResult(
                true, "SELECT", List.of("cnt"), List.of(Map.of("cnt", 7)), 1, 0, 3L, true, null));
    poller =
        new MetricPollerService(
            mock(DSLContext.class),
            snapshots,
            new AnomalyDetector(),
            mock(ApplicationEventPublisher.class),
            new ObjectMapper(),
            runner,
            tx,
            gate,
            mock(ClearanceResolver.class),
            aiCallContext,
            mock(AiHostingResolver.class),
            mock(DatasetAccessGuard.class),
            pipelineDataSchema);
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  /** 데이터셋 메트릭 하나를 가진 이상탐지 잡 한 건을 잡 조회 결과로 돌려준다. */
  private void givenDatasetMetricJob(String query) throws Exception {
    DSLContext ctx = DSL.using(SQLDialect.POSTGRES);
    var jobs =
        ctx.newResult(
            PROACTIVE_JOB.ID,
            PROACTIVE_JOB.USER_ID,
            PROACTIVE_JOB.CONFIG,
            PROACTIVE_JOB.TRIGGER_TYPE);
    var job =
        ctx.newRecord(
            PROACTIVE_JOB.ID,
            PROACTIVE_JOB.USER_ID,
            PROACTIVE_JOB.CONFIG,
            PROACTIVE_JOB.TRIGGER_TYPE);
    Map<String, Object> config =
        Map.of(
            "anomaly",
            Map.of("metrics", List.of(Map.of("id", "m1", "source", "dataset", "query", query))));
    job.values(JOB_ID, 2L, JSONB.valueOf(new ObjectMapper().writeValueAsString(config)), "ANOMALY");
    jobs.add(job);
    doReturn(jobs).when(tx).execute(any());
  }

  /** Tibero 테넌트의 스키마 한정 메트릭은 관문(executeMetricQuery)까지 가고, 값이 저장된다. */
  @Test
  void tiberoTenant_qualifiedMetric_reachesGate_andIsCollected() throws Exception {
    when(pipelineDataSchema.isTibero()).thenReturn(true);
    givenDatasetMetricJob("SELECT COUNT(*) AS cnt FROM genia_t5.orders");

    poller.poll();

    verify(gate)
        .executeMetricQuery(
            any(), argThat(n -> n.text().equals("SELECT COUNT(*) AS cnt FROM genia_t5.orders")));
    verify(snapshots).save(eq(JOB_ID), eq("m1"), eq(7.0), any());
  }

  /** Tibero 테넌트라도 사전 검증은 한다 — Tibero 검증기 기준이라 미한정 이름은 관문 전에 건너뛴다(경고만, 매 주기 오류 로그 없이). */
  @Test
  void tiberoTenant_unqualifiedMetric_isSkippedBeforeGate() throws Exception {
    when(pipelineDataSchema.isTibero()).thenReturn(true);
    givenDatasetMetricJob("SELECT COUNT(*) FROM orders");

    poller.poll();

    verify(gate, never()).executeMetricQuery(any(), any());
    verify(snapshots, never()).save(any(), any(), anyDouble(), any());
  }

  /** PG 테넌트는 기존 그대로 — PG 데이터 스키마 메트릭은 관문으로 가고, Tibero 스키마 메트릭은 PG 검증기가 건너뛴다. */
  @Test
  void pgTenant_keepsPgPreValidation() throws Exception {
    when(pipelineDataSchema.isTibero()).thenReturn(false);
    givenDatasetMetricJob("SELECT COUNT(*) AS cnt FROM genia_t5.orders");
    poller.poll();
    verify(gate, never()).executeMetricQuery(any(), any());

    String pgQuery = "SELECT COUNT(*) AS cnt FROM " + DataSchema.forTenant(TENANT_ID) + ".orders";
    givenDatasetMetricJob(pgQuery);
    poller.poll();
    verify(gate).executeMetricQuery(any(), argThat(n -> n.text().equals(pgQuery)));
    verify(snapshots).save(eq(JOB_ID), eq("m1"), eq(7.0), any());
  }
}
