package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.apiconnection.service.ApiConnectionService;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.service.DataTableRowService;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.DataStoreResolver;
import com.smartfirehub.global.security.PermissionChecker;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.dto.PipelineStepResponse;
import com.smartfirehub.pipeline.dto.StepCursor;
import com.smartfirehub.pipeline.repository.PipelineExecutionRepository;
import com.smartfirehub.pipeline.repository.PipelineRepository;
import com.smartfirehub.pipeline.repository.PipelineStepRepository;
import com.smartfirehub.pipeline.service.executor.AiClassifyExecutor;
import com.smartfirehub.pipeline.service.executor.ApiCallExecutor;
import com.smartfirehub.pipeline.service.executor.DbExecutorClient;
import com.smartfirehub.pipeline.service.executor.DbExecutorClient.ProbeColumn;
import com.smartfirehub.pipeline.service.executor.DbExecutorClient.ProbeResult;
import com.smartfirehub.pipeline.service.executor.ExecutorClient;
import com.smartfirehub.pipeline.service.executor.ExecutorClient.SqlExecuteResult;
import com.smartfirehub.pipeline.service.validator.PythonScriptValidator;
import com.smartfirehub.pipeline.service.validator.SqlValidator;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import com.smartfirehub.securitylevel.ai.AiHostingResolver;
import com.smartfirehub.securitylevel.pythonread.PythonReadGrants;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 러너가 스텝 방언(WD-231)에 일을 나눠 맡긴 뒤에도 Tibero 증분 SQL 단계의 사건 순서가 그대로인지 지키는 회귀 테스트.
 *
 * <p>순서는 「증분 후보 잡기(러너 → 방언) → 행 트리거 보장(러너 → 방언) → db-executor 실행(방언의 SQL 실행 본체)」이다. 후보는 SQL 실행 전에
 * 잡아야 실행 도중 커밋되는 행을 다음 실행이 놓치지 않고, 트리거는 실행 전에 보장해야 UPDATE 로 바뀐 행의 {@code _UPDATED_AT} 이 움직인다. 세 호출이
 * 서로 다른 객체(러너·방언 두 메서드)에 흩어졌으므로 러너 수준에서 한 번에 본다. 파일 이름은 기존 러너 테스트({@code PipelineAsyncRunner*Test},
 * 무수정 확인 대상)와 겹치지 않게 지었다.
 */
@ExtendWith(MockitoExtension.class)
class PipelineStepDialectOrderTest {

  private static final long TENANT_ID = 5L;
  private static final Long STEP_EXEC_ID = 900L;
  private static final Long USER_ID = 1L;
  private static final Long OUTPUT_ID = 10L;
  private static final Long STEP_ID = 100L;

  private static final OffsetDateTime LAST =
      OffsetDateTime.of(2026, 10, 24, 1, 2, 3, 0, ZoneOffset.UTC);
  private static final OffsetDateTime CANDIDATE =
      OffsetDateTime.of(2026, 10, 24, 4, 0, 0, 0, ZoneOffset.UTC);

  @Mock PipelineStepRepository stepRepository;
  @Mock PipelineExecutionRepository executionRepository;
  @Mock PipelineExecutionRegistry executionRegistry;
  @Mock PipelineTenantAdmission tenantAdmission;
  @Mock PipelineRepository pipelineRepository;
  @Mock DataTableService dataTableService;
  @Mock DataTableRowService dataTableRowService;
  @Mock DatasetRepository datasetRepository;
  @Mock DatasetColumnRepository columnRepository;
  @Mock SqlColumnProbe sqlColumnProbe;
  @Mock SqlScriptExecutor sqlExecutor;
  @Mock PythonScriptExecutor pythonExecutor;
  @Mock ApplicationEventPublisher applicationEventPublisher;
  @Mock ApiCallExecutor apiCallExecutor;
  @Mock AiClassifyExecutor aiClassifyExecutor;
  @Mock ApiConnectionService apiConnectionService;
  @Mock ObjectMapper objectMapper;
  @Mock PermissionChecker permissionChecker;
  @Mock TempDatasetService tempDatasetService;
  @Mock ExecutorClient executorClient;
  @Mock DbExecutorClient dbExecutorClient;

  private final DataStoreResolver dataStoreResolver = mock(DataStoreResolver.class);
  private final SqlValidator pgValidator = spy(new SqlValidator("data", true));

  @Spy
  PipelineDataSchema pipelineDataSchema = new PipelineDataSchema(dataStoreResolver, pgValidator);

  @Mock PythonScriptValidator pythonScriptValidator;
  @Mock IncrementalCursorService incrementalCursorService;
  @Mock IncrementalStepLock incrementalStepLock;
  @Mock TiberoIncrementalCursor tiberoIncrementalCursor;
  @Mock OutputTableSessionLock outputTableSessionLock;
  @Mock PipelineSecurityGate pipelineSecurityGate;
  @Mock DatasetAccessGuard datasetAccessGuard;
  @Mock AiHostingResolver aiHostingResolver;
  @Mock PythonReadGrants pythonReadGrantSync;
  @Mock LocalPythonOutputLoader localPythonOutputLoader;

  @InjectMocks PipelineAsyncRunner runner;

  @BeforeEach
  void tiberoTenant() {
    TenantContext.set(TENANT_ID);
    lenient().when(dataStoreResolver.currentBackend()).thenReturn(DataStoreBackend.TIBERO);
    lenient()
        .when(pipelineSecurityGate.runAs(any()))
        .thenAnswer(
            inv -> {
              Long u = inv.getArgument(0);
              return new PipelineSecurityGate.RunAs(u, Clearance.none(u, TENANT_ID));
            });
    when(datasetRepository.findTableNameById(OUTPUT_ID)).thenReturn(Optional.of("orders"));
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void tibero_incremental_order_candidateBeforeTriggersBeforeExecute() {
    when(stepRepository.findCursor(STEP_ID))
        .thenReturn(Optional.of(new StepCursor(LAST, false, OUTPUT_ID)));
    when(tiberoIncrementalCursor.captureCandidate()).thenReturn(CANDIDATE);
    when(dbExecutorClient.probe(anyString()))
        .thenReturn(
            new ProbeResult(
                true,
                List.of(
                    new ProbeColumn("K", Types.VARCHAR, "VARCHAR", 100, 0),
                    new ProbeColumn("V", Types.VARCHAR, "VARCHAR", 100, 0)),
                null,
                null));
    when(columnRepository.findByDatasetId(OUTPUT_ID))
        .thenReturn(List.of(col("k", true), col("v", false)));
    when(dbExecutorClient.executeSql(anyString(), anyList()))
        .thenReturn(
            new SqlExecuteResult(true, List.of(), List.of(), 2, "2 rows", null, null, null));

    String status =
        runner.executeStep(
            STEP_EXEC_ID,
            new PipelineStepResponse(
                STEP_ID,
                "s1",
                null,
                "SQL",
                "SELECT k, v FROM genia_t5.src_orders WHERE \"_UPDATED_AT\" >= {{last_run_at}}",
                OUTPUT_ID,
                null,
                List.of(),
                List.of(),
                0,
                "MERGE",
                null,
                null,
                null,
                null),
            77L,
            "P",
            USER_ID,
            false);

    assertThat(status).isEqualTo("COMPLETED");
    var order = inOrder(tiberoIncrementalCursor, dataTableService, dbExecutorClient);
    order.verify(tiberoIncrementalCursor).captureCandidate();
    order.verify(dataTableService).ensureRowTriggers(List.of("ORDERS", "SRC_ORDERS"));
    order.verify(dbExecutorClient).executeSql(anyString(), anyList());
    // PG 싱크·PG 후보 함수는 Tibero 테넌트에서 부르지 않는다.
    verifyNoInteractions(incrementalCursorService, executorClient, sqlExecutor, sqlColumnProbe);
  }

  private static DatasetColumnResponse col(String columnName, boolean isPrimaryKey) {
    return new DatasetColumnResponse(
        null, columnName, null, "TEXT", null, true, false, null, 0, isPrimaryKey);
  }
}
