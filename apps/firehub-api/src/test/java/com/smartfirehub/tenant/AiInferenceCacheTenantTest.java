package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.service.DataTableRowService;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.dto.AiClassifyConfig;
import com.smartfirehub.pipeline.dto.AiClassifyConfig.OutputColumn;
import com.smartfirehub.pipeline.dto.PipelineStepResponse;
import com.smartfirehub.pipeline.service.executor.AiAgentClient;
import com.smartfirehub.pipeline.service.executor.AiClassifyExecutor;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code ai_inference_cache} 가 테넌트별로 파티션되는지 검증한다 (P2-e R2 / Task 5).
 *
 * <p><b>무엇을 지키는가.</b> {@code row_hash} 는 분류 대상 <b>행 내용</b>의 해시다. 캐시를 테넌트
 * 간에 공유하면 A 테넌트가 어떤 행을 갖고 있는지와 그 추론 결과가 B 테넌트의 캐시 히트로 관측된다.
 * 그래서 같은 {@code (row_hash, prompt_version)} 이라도 서로의 캐시를 보지 못하고 각자 자기 것만
 * 히트해야 한다.
 *
 * <p><b>무엇이 격리를 성립시키는가.</b> 이 시점에는 V104 정책이 아직 없다. 따라서 격리는 정책이
 * 아니라 {@code AiClassifyExecutor} 캐시 조회의 <b>tenant_id 술어</b>로만 성립한다 — 그 술어를
 * 지우면 이 테스트는 "B 가 A 의 캐시를 히트"로 실패한다(변이 확인 완료). V104 이후에는 정책이
 * 같은 일을 이중으로 하지만, 술어는 방어 심층화로 남긴다(트랜잭션 밖 조회를 막지는 못하므로).
 *
 * <p><b>클래스 레벨 {@code @Transactional} 을 쓰지 않는다.</b> 검증 대상인 {@code
 * executor.execute} 는 반드시 트랜잭션 <b>밖</b>에서 불러야 한다 — 테스트가 트랜잭션을 열어 주면
 * GUC 가 공급되어 "프로덕션 경로가 스스로 좁은 트랜잭션을 연다"는 이번 배선을 검증하지 못한다.
 * 픽스처·정리·검증 조회만 {@code inTenantFixture} 로 감싼다.
 */
class AiInferenceCacheTenantTest extends IntegrationTestBase {

  private static final Table<?> AI_INFERENCE_CACHE = table(name("ai_inference_cache"));
  private static final Field<Long> CACHE_TENANT_ID = field(name("tenant_id"), Long.class);
  private static final Field<String> CACHE_ROW_HASH = field(name("row_hash"), String.class);
  private static final Field<String> CACHE_PROMPT_VERSION =
      field(name("prompt_version"), String.class);

  @Autowired private DSLContext dsl;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private TransactionTemplate transactionTemplate;

  private AiAgentClient aiAgentClient;
  private DataTableRowService dataTableRowService;
  private DataTableService dataTableService;
  private DatasetRepository datasetRepository;
  private AiClassifyExecutor executor;

  private long tenantA;
  private long tenantB;
  private String prompt;

  @BeforeEach
  void setUpExecutor() {
    // 실행마다 고유한 테넌트를 쓴다 — 공유 테스트 DB 에 남는 다른 세션의 캐시 행과 섞이지 않고,
    // 정리 DELETE 도 "내가 만든 테넌트의 행"으로 범위가 증명된다.
    tenantA = TenantRlsTestSupport.createActiveTenant(dsl, "ai-cache-a");
    tenantB = TenantRlsTestSupport.createActiveTenant(dsl, "ai-cache-b");
    // 프롬프트가 곧 prompt_version 해시의 입력이라, 실행마다 다르게 해서 캐시 키를 이 실행에 묶는다.
    prompt = "Classify rows " + TenantRlsTestSupport.nextTenantId();

    aiAgentClient = mock(AiAgentClient.class);
    dataTableRowService = mock(DataTableRowService.class);
    dataTableService = mock(DataTableService.class);
    datasetRepository = mock(DatasetRepository.class);

    // DB(캐시)만 실물이고 데이터 테이블·AI 에이전트는 mock 이다. 이 테스트의 관심사는 캐시 격리뿐.
    executor =
        new AiClassifyExecutor(
            aiAgentClient,
            dataTableRowService,
            dataTableService,
            datasetRepository,
            objectMapper,
            dsl,
            transactionTemplate);

    when(datasetRepository.findTableNameById(200L)).thenReturn(Optional.of("output_table"));
    when(datasetRepository.findTableNameById(1L)).thenReturn(Optional.of("source_table"));
    when(dataTableRowService.countRows("source_table")).thenReturn(1L);

    // 두 테넌트에 **완전히 같은** 입력 행을 준다 — row_hash 가 같아야 "키가 겹치는데도 격리된다"를
    // 증명할 수 있다. 행이 달라 해시가 갈리면 테스트가 엉뚱한 이유로 통과한다.
    Map<String, Object> sourceRow = new HashMap<>();
    sourceRow.put("id", 42L);
    sourceRow.put("text", "동일한 행 내용");
    when(dataTableRowService.queryData(any(), any(), any(), anyInt(), anyInt()))
        .thenReturn(List.of(sourceRow));

    AiAgentClient.ClassifyRowResult aiRow =
        new AiAgentClient.ClassifyRowResult(Map.of("source_id", 42L, "category", "animal"));
    when(aiAgentClient.classify(any(), anyLong()))
        .thenReturn(new AiAgentClient.ClassifyResponse(List.of(aiRow), 1, "claude"));
  }

  @AfterEach
  void cleanUp() {
    // 내가 만든 두 테넌트의 행만 지운다. 정리도 트랜잭션 안에서 — V104 정책이 켜지면 트랜잭션
    // 밖 DELETE 는 조용히 0행이 되어 픽스처가 누적되고, 한참 뒤 무관한 테스트가 깨진다(P2-d 전례).
    inTenantFixture(tenantA, () -> dsl.deleteFrom(AI_INFERENCE_CACHE).where(CACHE_TENANT_ID.eq(tenantA)).execute());
    inTenantFixture(tenantB, () -> dsl.deleteFrom(AI_INFERENCE_CACHE).where(CACHE_TENANT_ID.eq(tenantB)).execute());
    // tenant 는 경계 위의 전역 테이블(RLS 없음)이라 컨텍스트 없이 지울 수 있다.
    dsl.deleteFrom(table(name("tenant")))
        .where(field(name("id"), Long.class).in(tenantA, tenantB))
        .execute();
  }

  @Test
  @DisplayName("같은 row_hash+prompt_version 이라도 테넌트는 서로의 캐시를 보지 못하고 자기 것만 히트한다")
  void cacheIsPartitionedPerTenant() {
    PipelineStepResponse step = buildStep();

    // 1) 테넌트 A 첫 실행 — 캐시 미스라 AI 를 부르고 자기 테넌트 캐시에 적재한다.
    //    프로덕션 호출은 트랜잭션 밖에서(컨텍스트만 세워서) — 실행기가 스스로 트랜잭션을 열어야 한다.
    AiClassifyExecutor.ExecutionResult firstA =
        TenantContext.runScopedGet(tenantA, () -> executor.execute(step, 100L, 1L));
    assertThat(firstA.executionLog()).contains("1 AI-processed");
    verify(aiAgentClient, times(1)).classify(any(), anyLong());

    // 2) 테넌트 A 두 번째 실행 — 자기 캐시를 히트해 AI 를 다시 부르지 않는다.
    AiClassifyExecutor.ExecutionResult secondA =
        TenantContext.runScopedGet(tenantA, () -> executor.execute(step, 100L, 1L));
    assertThat(secondA.executionLog()).contains("1 cached");
    verify(aiAgentClient, times(1)).classify(any(), anyLong());

    // 3) 테넌트 B 첫 실행 — 키가 A 와 완전히 같지만 A 의 캐시를 보면 안 되므로 미스여야 한다.
    //    여기서 히트가 나면 A 테넌트 행의 내용·추론 결과가 B 에게 관측된 것이다(R2 가 막으려는 것).
    AiClassifyExecutor.ExecutionResult firstB =
        TenantContext.runScopedGet(tenantB, () -> executor.execute(step, 100L, 1L));
    assertThat(firstB.executionLog()).contains("1 AI-processed");
    verify(aiAgentClient, times(2)).classify(any(), anyLong());

    // 4) 테넌트 B 두 번째 실행 — 이제는 B 자신의 캐시를 히트한다(격리가 "전부 미스"로 성립한 게 아님).
    AiClassifyExecutor.ExecutionResult secondB =
        TenantContext.runScopedGet(tenantB, () -> executor.execute(step, 100L, 1L));
    assertThat(secondB.executionLog()).contains("1 cached");
    verify(aiAgentClient, times(2)).classify(any(), anyLong());

    // 5) 실제로 키가 겹쳤는지 DB 로 확인한다. 이 단언이 없으면 "해시가 테넌트마다 달라져서" 통과하는
    //    가짜 초록을 구분할 수 없다. V103 이 유니크를 (tenant_id, row_hash, prompt_version) 으로
    //    접었기 때문에 같은 키가 두 행으로 공존할 수 있다.
    //
    //    <b>V104 이후에는 한 컨텍스트에서 두 행을 함께 볼 수 없다.</b> 예전에는 tenantA 컨텍스트에서
    //    in(tenantA, tenantB) 로 2행을 조회했지만, 정책이 켜진 지금 그건 1행이 된다 — 결함이 아니라
    //    정책이 의도대로 도는 증거다. 그래서 각 테넌트에서 자기 행만 조회해 <b>각각 1행</b>임을
    //    단언하고(= 각 테넌트는 자기 것만 본다), 두 행의 키가 서로 같은지 비교한다(= 키가 겹친 채
    //    공존한다 = 파티션 성립). 원래 단언의 목적은 그대로 두고 격리 증명이 하나 늘었다.
    Record rowA = fetchSoleCacheRow(tenantA);
    Record rowB = fetchSoleCacheRow(tenantB);

    assertThat(rowA.get(CACHE_TENANT_ID)).isEqualTo(tenantA);
    assertThat(rowB.get(CACHE_TENANT_ID)).isEqualTo(tenantB);
    assertThat(rowA.get(CACHE_ROW_HASH))
        .as("두 테넌트의 캐시 키가 같아야 '키가 겹쳤는데도 분리됐다'가 증명된다")
        .isNotNull()
        .isEqualTo(rowB.get(CACHE_ROW_HASH));
    assertThat(rowA.get(CACHE_PROMPT_VERSION)).isEqualTo(rowB.get(CACHE_PROMPT_VERSION));
  }

  /**
   * 해당 테넌트 컨텍스트에서 보이는 캐시 행을 가져온다. 정확히 1행이어야 한다 — 남의 테넌트 행이
   * 함께 보이면(2행) 격리 실패고, 0행이면 자기 행조차 못 보는 것이다.
   *
   * <p>조회에 {@code tenant_id} 술어를 두지 않는 것이 요점이다. 술어를 걸면 이 단언이 검사하는 것이
   * 정책이 아니라 WHERE 절이 된다. 대신 {@code @BeforeEach} 가 실행마다 고유한 테넌트 두 개를 만들어
   * 공유 테스트 DB 의 남의 행이 섞이지 않게 한다.
   */
  private Record fetchSoleCacheRow(long tenantId) {
    List<? extends Record> rows =
        inTenantFixture(
            tenantId,
            () ->
                dsl.select(CACHE_TENANT_ID, CACHE_ROW_HASH, CACHE_PROMPT_VERSION)
                    .from(AI_INFERENCE_CACHE)
                    .fetch());
    assertThat(rows).as("테넌트 %s 컨텍스트에서 보이는 캐시 행", tenantId).hasSize(1);
    return rows.get(0);
  }

  @Test
  @DisplayName("테넌트 컨텍스트가 없으면 배치 루프 전에 즉시 실패한다 (onError=CONTINUE 가 삼키지 못한다)")
  void failsClosedWithoutTenantContext() {
    PipelineStepResponse step = buildStep();

    // IntegrationTestBase 가 세워 둔 기본 테넌트를 지워 "컨텍스트 없는 배경 스레드"를 재현한다.
    TenantContext.clear();

    // 배치 루프 **안**에서 던지면 onError=CONTINUE 기본값이 예외를 삼켜 "0행 출력 + 1 batch errors"
    // 로 성공 반환한다 — 배선 결함이 다시 조용해진다. 그래서 예외가 밖으로 나오는지를 단언한다.
    assertThatThrownBy(() -> executor.execute(step, 100L, 1L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("테넌트 컨텍스트 없이 AI_CLASSIFY");

    verifyNoInteractions(aiAgentClient);
  }

  /** AI_CLASSIFY 스텝 하나. APPEND 라 임시 테이블 스왑 배관을 타지 않는다. */
  private PipelineStepResponse buildStep() {
    AiClassifyConfig config =
        new AiClassifyConfig(
            prompt, List.of(new OutputColumn("category", "TEXT")), List.of("id", "text"), 20, "CONTINUE");
    @SuppressWarnings("unchecked")
    Map<String, Object> aiConfig = objectMapper.convertValue(config, Map.class);
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
        "APPEND",
        null,
        aiConfig,
        null,
        null);
  }
}
