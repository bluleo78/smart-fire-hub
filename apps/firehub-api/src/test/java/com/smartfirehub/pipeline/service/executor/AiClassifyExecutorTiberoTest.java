package com.smartfirehub.pipeline.service.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.service.DataTableRowService;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.dto.AiClassifyConfig;
import com.smartfirehub.pipeline.dto.AiClassifyConfig.OutputColumn;
import com.smartfirehub.pipeline.dto.PipelineStepResponse;
import com.smartfirehub.pipeline.repository.PipelineExecutionRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.InsertSetStep;
import org.jooq.SelectConditionStep;
import org.jooq.SelectJoinStep;
import org.jooq.SelectSelectStep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tibero 테넌트의 AI 분류 REPLACE(WD-77 Task 6) — 임시 테이블이 스테이징({@code STG_<24HEX>})이므로 쓰기는 임시 테이블 전용 창구
 * ({@code insertTempBatch})로만 가야 한다. 실제 테이블용 {@code insertBatch} 가 {@code STG_} 이름에 불리면 스테이징에 없는
 * {@code ID}·{@code _UPDATED_AT} 을 쓰는 INSERT 가 되어 실패한다(발견 고정: {@code TiberoDataRowStoreTest}). 또 PG
 * 트랜잭션을 LLM 호출·Tibero 쓰기 동안 쥐지 않는다(스펙 §4.3 — 쓰기 시점 {@code isActualTransactionActive()==false}).
 */
class AiClassifyExecutorTiberoTest {

  private static final String STG = "STG_0123456789ABCDEF01234567";

  private AiAgentClient aiAgentClient;
  private DataTableRowService dataTableRowService;
  private DataTableService dataTableService;
  private DatasetRepository datasetRepository;
  private DSLContext dsl;
  private AiClassifyExecutor executor;

  @BeforeEach
  @SuppressWarnings({"unchecked", "rawtypes"})
  void setUp() {
    aiAgentClient = mock(AiAgentClient.class);
    dataTableRowService = mock(DataTableRowService.class);
    dataTableService = mock(DataTableService.class);
    when(dataTableService.createTempTable("output_table")).thenReturn(STG);
    datasetRepository = mock(DatasetRepository.class);
    dsl = mock(DSLContext.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
    TenantContext.set(1L);
    AiClassifyTargetResolver targetResolver = mock(AiClassifyTargetResolver.class);
    when(targetResolver.resolve()).thenReturn(new AiClassifyTarget.UseChat());
    // 입력 데이터셋 컬럼 = text(WD-215) — 실행기가 고른 입력 컬럼을 데이터셋 컬럼과 대조하므로 있는 컬럼으로 세운다.
    DatasetColumnRepository datasetColumnRepository = mock(DatasetColumnRepository.class);
    when(datasetColumnRepository.findByDatasetId(anyLong()))
        .thenReturn(
            List.of(
                new DatasetColumnResponse(
                    1L, "text", "text", "TEXT", null, true, false, null, 0, false)));
    executor =
        new AiClassifyExecutor(
            aiAgentClient,
            dataTableRowService,
            dataTableService,
            datasetRepository,
            new ObjectMapper(),
            dsl,
            new TransactionTemplate(
                mock(org.springframework.transaction.PlatformTransactionManager.class)),
            mock(PipelineExecutionRepository.class),
            targetResolver,
            datasetColumnRepository);

    when(datasetRepository.findTableNameById(200L)).thenReturn(Optional.of("output_table"));
    when(datasetRepository.findTableNameById(1L)).thenReturn(Optional.of("source_table"));
    when(dataTableRowService.countRows("source_table")).thenReturn(1L);
    Map<String, Object> source = new HashMap<>();
    source.put("id", 7L);
    source.put("text", "world");
    when(dataTableRowService.queryData(anyString(), any(), any(), anyInt(), anyInt()))
        .thenReturn(List.of(source));

    // 캐시 조회는 미스
    SelectSelectStep selectStep = mock(SelectSelectStep.class);
    SelectJoinStep joinStep = mock(SelectJoinStep.class);
    SelectConditionStep cond1 = mock(SelectConditionStep.class);
    SelectConditionStep cond2 = mock(SelectConditionStep.class);
    when(dsl.select(any(org.jooq.Field.class))).thenReturn(selectStep);
    when(selectStep.from(any(org.jooq.Table.class))).thenReturn(joinStep);
    when(joinStep.where(any(org.jooq.Condition.class))).thenReturn(cond1);
    when(cond1.and(any(org.jooq.Condition.class))).thenReturn(cond2);
    when(cond2.and(any(org.jooq.Condition.class))).thenReturn(cond2);
    when(cond2.fetchOne()).thenReturn(null);
    InsertSetStep insertSetStep = mock(InsertSetStep.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
    when(dsl.insertInto(any(org.jooq.Table.class))).thenReturn(insertSetStep);
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  private PipelineStepResponse step(String loadStrategy) {
    AiClassifyConfig config =
        new AiClassifyConfig(
            "Classify rows",
            List.of(new OutputColumn("category", "TEXT")),
            List.of("id", "text"),
            20,
            "CONTINUE");
    @SuppressWarnings("unchecked")
    Map<String, Object> aiConfig = new ObjectMapper().convertValue(config, Map.class);
    return new PipelineStepResponse(
        10L,
        "AI Step",
        "desc",
        "AI_CLASSIFY",
        null,
        200L,
        "output_table",
        List.of(1L),
        List.of(),
        1,
        loadStrategy,
        null,
        aiConfig,
        null,
        null);
  }

  @Test
  void replace_writesEveryBatchToTheStagingThroughTheTempBatchWindow_thenSwaps() {
    when(aiAgentClient.classify(any(), any(), eq(1L)))
        .thenAnswer(
            inv -> {
              return new AiAgentClient.ClassifyResponse(
                  List.of(
                      new AiAgentClient.ClassifyRowResult(Map.of("source_id", 7, "category", "A"))),
                  1,
                  "m");
            });
    AiClassifyExecutor.ExecutionResult result = executor.execute(step("REPLACE"), 100L, 1L);

    assertThat(result.outputRows()).isEqualTo(1);
    InOrder order = inOrder(dataTableService, dataTableRowService);
    order.verify(dataTableService).createTempTable("output_table");
    order.verify(dataTableRowService).insertTempBatch(eq(STG), anyList(), anyList(), anyMap());
    order.verify(dataTableService).swapTable("output_table", STG);
    // 핵심 고정: 스테이징 이름에 실제 테이블용 insertBatch 가 불리지 않는다.
    verify(dataTableRowService, never()).insertBatch(anyString(), anyList(), anyList(), anyMap());
    verify(dataTableService, never()).dropTempTable(anyString(), anyString());
  }

  @Test
  void replace_zeroClassifiedRows_failsAndDropsStaging_withoutSwapOrWrite() {
    when(aiAgentClient.classify(any(), any(), anyLong()))
        .thenReturn(new AiAgentClient.ClassifyResponse(List.of(), 0, "m"));

    assertThatThrownBy(() -> executor.execute(step("REPLACE"), 100L, 1L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("0행");

    verify(dataTableService).dropTempTable("output_table", STG);
    verify(dataTableService, never()).swapTable(anyString(), anyString());
    verify(dataTableRowService, never())
        .insertTempBatch(anyString(), anyList(), anyList(), anyMap());
    verify(dataTableRowService, never()).insertBatch(anyString(), anyList(), anyList(), anyMap());
  }

  @Test
  void append_writesDirectlyToTheRealTable_withoutStaging() {
    when(aiAgentClient.classify(any(), any(), eq(1L)))
        .thenReturn(
            new AiAgentClient.ClassifyResponse(
                List.of(
                    new AiAgentClient.ClassifyRowResult(Map.of("source_id", 7, "category", "A"))),
                1,
                "m"));

    executor.execute(step("APPEND"), 100L, 1L);

    // 결정 3: AI 분류 APPEND 는 PG 와 같은 커밋 특성 — 실제 출력에 배치마다 직접 쓴다.
    verify(dataTableService, never()).createTempTable(anyString());
    verify(dataTableRowService).insertBatch(eq("output_table"), anyList(), anyList(), anyMap());
    verify(dataTableRowService, never())
        .insertTempBatch(anyString(), anyList(), anyList(), anyMap());
  }

  /**
   * 스펙 §4.3: LLM 호출·Tibero 쓰기 동안 PG 트랜잭션(연결)을 쥐지 않는다. 런타임 확인(isActualTransactionActive)은 바깥 트랜잭션도
   * 없고 트랜잭션 매니저도 목이라 실패할 수 없으므로, 실패할 수 있는 구조 검사로 고정한다 — 단계 실행 경로(러너·AI 분류 실행기·적재 창구·Tibero 행 저장소)의
   * 클래스와 메서드 어디에도 {@code @Transactional} 이 없어야 한다(누가 붙이면 LLM 호출 내내 PG 연결을 쥔다).
   */
  @Test
  void stepExecutionPath_carriesNoTransactionalAnnotation() {
    List<Class<?>> path =
        List.of(
            com.smartfirehub.pipeline.service.PipelineAsyncRunner.class,
            AiClassifyExecutor.class,
            com.smartfirehub.dataset.service.DataTableRowService.class,
            com.smartfirehub.dataset.storage.tibero.TiberoDataRowStore.class);
    List<String> offenders = new ArrayList<>();
    for (Class<?> type : path) {
      if (transactional(type.getAnnotations())) {
        offenders.add(type.getSimpleName() + "(class)");
      }
      for (java.lang.reflect.Method m : type.getDeclaredMethods()) {
        if (transactional(m.getAnnotations())) {
          offenders.add(type.getSimpleName() + "#" + m.getName());
        }
      }
    }
    assertThat(offenders).isEmpty();
  }

  private static boolean transactional(java.lang.annotation.Annotation[] annotations) {
    for (java.lang.annotation.Annotation a : annotations) {
      String n = a.annotationType().getName();
      if (n.equals("org.springframework.transaction.annotation.Transactional")
          || n.equals("jakarta.transaction.Transactional")) {
        return true;
      }
    }
    return false;
  }
}
