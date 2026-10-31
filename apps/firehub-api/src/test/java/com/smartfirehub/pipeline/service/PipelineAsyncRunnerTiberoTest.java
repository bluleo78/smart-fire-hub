package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
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
import com.smartfirehub.dataset.storage.tibero.TiberoPipelineSql;
import com.smartfirehub.global.security.PermissionChecker;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.dto.PipelineStepResponse;
import com.smartfirehub.pipeline.exception.ScriptExecutionException;
import com.smartfirehub.pipeline.repository.PipelineExecutionRepository;
import com.smartfirehub.pipeline.repository.PipelineRepository;
import com.smartfirehub.pipeline.repository.PipelineStepRepository;
import com.smartfirehub.pipeline.service.executor.AiClassifyExecutor;
import com.smartfirehub.pipeline.service.executor.ApiCallExecutor;
import com.smartfirehub.pipeline.service.executor.DbExecutorClient;
import com.smartfirehub.pipeline.service.executor.DbExecutorClient.ProbeColumn;
import com.smartfirehub.pipeline.service.executor.DbExecutorClient.ProbeResult;
import com.smartfirehub.pipeline.service.executor.ExecutionCancelCheck;
import com.smartfirehub.pipeline.service.executor.ExecutorClient;
import com.smartfirehub.pipeline.service.executor.ExecutorClient.DbError;
import com.smartfirehub.pipeline.service.executor.ExecutorClient.SqlExecuteResult;
import com.smartfirehub.pipeline.service.validator.PythonScriptValidator;
import com.smartfirehub.pipeline.service.validator.SqlValidator;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import com.smartfirehub.securitylevel.access.SqlAccessResult;
import com.smartfirehub.securitylevel.ai.AiHostingResolver;
import com.smartfirehub.securitylevel.pythonread.PythonReadGrants;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Tibero 저장소 테넌트의 파이프라인 단계 라우팅 단위 테스트(WD-77, 스펙 §5.1·§8).
 *
 * <p>고정하는 것:
 *
 * <ul>
 *   <li>Tibero 테넌트 SQL 단계는 {@link DbExecutorClient} 로만 간다 — PG 실행기({@link ExecutorClient}·{@link
 *       SqlScriptExecutor}·{@link SqlColumnProbe})는 {@code executorEnabled} 값과 무관하게 한 번도 불리지 않는다(스펙
 *       §2 실패 조건 1).
 *   <li>REPLACE/APPEND/MERGE 마다 실행기에 넘기는 문장·선행문(Tibero 판 비우기·INSERT…SELECT·MERGE).
 *   <li>probe 결과의 대문자 컬럼명이 메타데이터의 소문자 컬럼명과 맞춰진다.
 *   <li>Python 단계의 Tibero 흐름(슬롯·토큰·스테이징)은 {@code PipelineAsyncRunnerTiberoPythonTest} 가 본다.
 *   <li>실행기 실패 응답은 Tibero 코드로 번역된 문구로 단계 실패가 된다.
 *   <li>{@code {{#N}}} 참조가 {@code "GENIA_T5"."<TABLE>"} 로 치환된다(Task 2 에서 미뤄 둔 러너 단위 테스트).
 * </ul>
 *
 * <p>테넌트 백엔드 판정은 목이 아니라 실제 {@link PipelineDataSchema}(저장소 판정기만 목)를 쓴다 — 검증기·스키마 이름이 실제 규칙(GENIA_T5,
 * Tibero 가드)대로 나와야 러너가 판정한 문자열과 실행하는 문자열이 같은지 볼 수 있다.
 */
@ExtendWith(MockitoExtension.class)
class PipelineAsyncRunnerTiberoTest {

  private static final long TENANT_ID = 5L;
  private static final String SCHEMA = "GENIA_T5";
  private static final Long STEP_EXEC_ID = 900L;
  private static final Long PIPELINE_ID = 77L;
  private static final Long USER_ID = 1L;
  private static final Long OUTPUT_ID = 10L;
  private static final String OUTPUT_TABLE = "orders";

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

  /** 테넌트 저장소 판정기 — 이 테스트의 유일한 백엔드 입력. @BeforeEach 에서 TIBERO 로 고정한다. */
  private final DataStoreResolver dataStoreResolver = mock(DataStoreResolver.class);

  /** PG 테넌트용 기본 검증기 — Tibero 테넌트에서는 쓰이지 않아야 한다(PipelineDataSchema 가 Tibero 검증기를 고른다). */
  private final SqlValidator pgValidator = spy(new SqlValidator("data", true));

  @Spy
  PipelineDataSchema pipelineDataSchema = new PipelineDataSchema(dataStoreResolver, pgValidator);

  @Mock PythonScriptValidator pythonScriptValidator;
  @Mock IncrementalCursorService incrementalCursorService;
  @Mock OutputTableSessionLock outputTableSessionLock;
  @Mock IncrementalStepLock incrementalStepLock;
  @Mock PipelineSecurityGate pipelineSecurityGate;
  @Mock DatasetAccessGuard datasetAccessGuard;
  @Mock AiHostingResolver aiHostingResolver;
  @Mock PythonReadGrants pythonReadGrantSync;
  @Mock LocalPythonOutputLoader localPythonOutputLoader;

  @InjectMocks PipelineAsyncRunner runner;

  @BeforeEach
  void tiberoTenant() {
    // 운영에서는 pipelineExecutor 의 TenantContextTaskDecorator 가 승계하는 테넌트 — 여기서는 직접 세운다.
    TenantContext.set(TENANT_ID);
    lenient().when(dataStoreResolver.currentBackend()).thenReturn(DataStoreBackend.TIBERO);
    // 보안 게이트 판정 자체는 PipelineSqlAccessTest 등 통합 테스트 몫이다 — 여기서는 통과(목 기본값)로 두고 라우팅만 본다.
    // 사용자 SQL 판정 기본값(WD-426) — 아무 데이터셋도 참조하지 않는 허용 결과. 판정 결과를 쓰는 테스트는 자기 스텁으로 덮어쓴다.
    RunnerSqlAccessStubs.allowSqlWithoutDatasetsByDefault(pipelineSecurityGate);
    lenient()
        .when(pipelineSecurityGate.runAs(any()))
        .thenAnswer(
            inv -> {
              Long u = inv.getArgument(0);
              return new PipelineSecurityGate.RunAs(u, Clearance.none(u, TENANT_ID));
            });
    lenient()
        .when(datasetRepository.findTableNameById(OUTPUT_ID))
        .thenReturn(Optional.of(OUTPUT_TABLE));
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  // ── SQL SELECT 적재: REPLACE / APPEND / MERGE ─────────────────────────────────────────────

  @Test
  void tiberoSelectReplace_sendsInsertSelectWithTiberoDeleteToDbExecutor() {
    String sql = "SELECT amount, name FROM genia_t5.src_orders";
    stubProbe("AMOUNT", "NAME");
    stubOutputColumns(col("amount", false), col("name", false));
    stubExecuteSuccess();

    String status = run(sqlStep(sql, OUTPUT_ID, "REPLACE"), false);

    assertThat(status).isEqualTo("COMPLETED");
    SentSql sent = captureSent();
    assertThat(sent.query())
        .isEqualTo(
            TiberoPipelineSql.insertSelect(SCHEMA, OUTPUT_TABLE, List.of("amount", "name"), sql));
    assertThat(sent.query())
        .startsWith(
            "INSERT INTO \"GENIA_T5\".\"ORDERS\" (\"ID\", \"AMOUNT\", \"NAME\", \"_UPDATED_AT\")");
    // 비우기는 본 문장과 같은 요청(=같은 트랜잭션)의 선행문 — 실행기 선행문 정규식과 글자 단위로 같아야 한다.
    assertThat(sent.preStatements()).containsExactly("DELETE FROM \"GENIA_T5\".\"ORDERS\"");
    // probe 도 Tibero 실행기로 — 검증·게이트를 통과한 바로 그 문자열이다.
    verify(dbExecutorClient).probe(sql);
    verifyNoPgExecution();
  }

  @Test
  void tiberoSelectAppend_sendsInsertSelectWithoutPreStatements() {
    String sql = "SELECT amount, name FROM genia_t5.src_orders";
    stubProbe("AMOUNT", "NAME");
    stubOutputColumns(col("amount", false), col("name", false));
    stubExecuteSuccess();

    String status = run(sqlStep(sql, OUTPUT_ID, "APPEND"), true);

    assertThat(status).isEqualTo("COMPLETED");
    SentSql sent = captureSent();
    assertThat(sent.query())
        .isEqualTo(
            TiberoPipelineSql.insertSelect(SCHEMA, OUTPUT_TABLE, List.of("amount", "name"), sql));
    assertThat(sent.preStatements()).isEmpty();
    verifyNoPgExecution();
  }

  @Test
  void tiberoSelectMerge_sendsMergeSelectKeyedOnDatasetPk() {
    String sql = "SELECT k, v FROM genia_t5.src_orders";
    stubProbe("K", "V");
    stubOutputColumns(col("k", true), col("v", false));
    stubExecuteSuccess();

    String status = run(sqlStep(sql, OUTPUT_ID, "MERGE"), false);

    assertThat(status).isEqualTo("COMPLETED");
    SentSql sent = captureSent();
    assertThat(sent.query())
        .isEqualTo(
            TiberoPipelineSql.mergeSelect(
                SCHEMA, OUTPUT_TABLE, List.of("k", "v"), List.of("k"), sql));
    assertThat(sent.query()).startsWith("MERGE INTO \"GENIA_T5\".\"ORDERS\"");
    // MERGE 는 출력을 비우지 않는다(PG 와 같은 계약).
    assertThat(sent.preStatements()).isEmpty();
    verifyNoPgExecution();
  }

  // ── REPLACE 가 자기 출력을 읽음(WD-426) ─────────────────────────────────────────────────

  /** 보안 판정이 사용자 SQL 이 출력 자신을 읽는다고 본 결과(읽기 = 출력). */
  private void stubSqlReadsOutput(Long outputId) {
    when(pipelineSecurityGate.checkStepSqlForRun(any(), any()))
        .thenReturn(new SqlAccessResult(true, null, null, null, Set.of(outputId), Set.of(), true));
  }

  /**
   * Tibero 에는 비우기 전 출력을 읽는 한 문장 형태가 없고 db-executor 선행문 규칙도 잠금 전용 형태를 받지 않는다 — 출력 자신을 읽는 SELECT +
   * REPLACE 는 저장 검증을 우회해 와도 실행 전에 실패한다. db-executor 에는 probe·실행 어느 요청도 가지 않는다(새 선행문 형태가 Tibero 로 가는
   * 일이 없다).
   */
  @Test
  void tiberoSelectReplace_readingItsOwnOutput_failsBeforeAnyDbExecutorCall() {
    stubSqlReadsOutput(OUTPUT_ID);

    String status =
        run(
            sqlStep("SELECT amount + 1 AS amount FROM genia_t5.orders", OUTPUT_ID, "REPLACE"),
            false);

    assertThat(status).isEqualTo("FAILED");
    assertThat(failureMessage())
        .startsWith("Tibero 저장소에서는 교체(REPLACE) 스텝의 SQL 이 출력 데이터셋 자신을 읽거나 고칠 수 없습니다")
        .endsWith(".");
    verifyNoInteractions(dbExecutorClient);
    verifyNoPgExecution();
  }

  /**
   * 출력 미지정(러너 TEMP) SELECT 가 이 스텝의 기존 TEMP 를 읽어도 같다 — 거부가 TEMP 준비(맞춤·재생성·생성)와 probe 보다 먼저라 아무것도
   * 만들거나 지우지 않는다.
   */
  @Test
  void tiberoSelectReplace_readingItsOwnTemp_failsBeforeTempPreparationAndProbe() {
    Long tempId = 33L;
    stubSqlReadsOutput(tempId);
    when(tempDatasetService.findExistingTempDataset(100L)).thenReturn(Optional.of(tempId));

    String status =
        run(sqlStep("SELECT amount FROM genia_t5.pipeline_tmp_s1", null, "REPLACE"), false);

    assertThat(status).isEqualTo("FAILED");
    assertThat(failureMessage()).startsWith("Tibero 저장소에서는 교체(REPLACE)");
    verify(tempDatasetService, never()).reconcileTempDataset(any());
    verify(tempDatasetService, never()).hasSchemaChanged(any(), any());
    verify(tempDatasetService, never()).createTempDataset(any(), any(), any(), any(), any(), any());
    verifyNoInteractions(dbExecutorClient);
  }

  /** Tibero 사용자 DML 이 출력 자신을 고쳐도 같은 Tibero 문구로 실행 전에 실패한다. */
  @Test
  void tiberoDmlReplace_updatingItsOwnOutput_failsBeforeAnyDbExecutorCall() {
    when(pipelineSecurityGate.checkStepSqlForRun(any(), any()))
        .thenReturn(new SqlAccessResult(true, null, null, null, Set.of(), Set.of(OUTPUT_ID), true));

    String status =
        run(sqlStep("UPDATE genia_t5.orders SET amount = 0", OUTPUT_ID, "REPLACE"), false);

    assertThat(status).isEqualTo("FAILED");
    assertThat(failureMessage()).startsWith("Tibero 저장소에서는 교체(REPLACE)");
    verifyNoInteractions(dbExecutorClient);
  }

  /**
   * 사용자 DML(UPDATE·DELETE)은 문장을 그대로 보낸다. REPLACE 면 PG 경로(#735)와 같이 출력 비우기 선행문을 같은 트랜잭션으로 붙인다. 사용자
   * INSERT 는 트리거 보장 뒤 실행한다(PipelineAsyncRunnerTiberoInsertTest).
   */
  @Test
  void tiberoDmlReplace_sendsUserSqlVerbatimWithTiberoDelete() {
    String sql = "UPDATE genia_t5.orders SET amount = 0 WHERE amount < 0";
    stubExecuteSuccess();

    String status = run(sqlStep(sql, OUTPUT_ID, "REPLACE"), false);

    assertThat(status).isEqualTo("COMPLETED");
    SentSql sent = captureSent();
    assertThat(sent.query()).isEqualTo(sql);
    assertThat(sent.preStatements()).containsExactly("DELETE FROM \"GENIA_T5\".\"ORDERS\"");
    verify(dbExecutorClient, never()).probe(anyString());
    verifyNoPgExecution();
  }

  /**
   * WD-153 협조적 취소 — Tibero 경로도 PG 경로처럼 db-executor 로 보내기 직전에 취소를 확인한다. 취소가 이미 요청돼 있으면 문장을 보내지 않고
   * (REPLACE 비우기도 그 요청 안이라 출력이 그대로다) 단계를 CANCELLED 로 기록한다.
   */
  @Test
  void tiberoSqlStep_cancelRequestedBeforeExecution_neverCallsDbExecutor() {
    String sql = "UPDATE genia_t5.orders SET amount = 0 WHERE amount < 0";

    String status =
        runner.executeStep(
            STEP_EXEC_ID,
            sqlStep(sql, OUTPUT_ID, "REPLACE"),
            PIPELINE_ID,
            "P",
            USER_ID,
            false,
            () -> true);

    assertThat(status).isEqualTo("CANCELLED");
    verify(dbExecutorClient, never()).executeSql(anyString(), anyList(), nullable(Long.class));
    verify(executionRepository)
        .updateStepExecution(
            eq(STEP_EXEC_ID),
            eq("CANCELLED"),
            isNull(),
            isNull(),
            contains("출력 데이터는 바뀌지 않았습니다"),
            isNull(),
            any());
    verifyNoPgExecution();
  }

  /**
   * 잠금 대기 중 취소(WD-482): 러너는 실행 id 를 db-executor 에 넘기고, db-executor 가 출력 잠금(LOCK TABLE)을 얻은 뒤 취소를
   * 확인해 롤백한 응답(cancelled=true)을 받으면 단계를 실패가 아니라 CANCELLED 로 기록한다 — 출력은 손대지 않았다.
   */
  @Test
  void tiberoSqlStep_cancelledByDbExecutorAfterLock_recordsCancelled() {
    String sql = "UPDATE genia_t5.orders SET amount = 0 WHERE amount < 0";
    when(dbExecutorClient.executeSql(anyString(), anyList(), nullable(Long.class)))
        .thenReturn(new SqlExecuteResult(false, null, null, 0, "", "취소", null, null, true));

    String status =
        runner.executeStep(
            STEP_EXEC_ID,
            sqlStep(sql, OUTPUT_ID, "REPLACE"),
            PIPELINE_ID,
            "P",
            USER_ID,
            false,
            ExecutionCancelCheck.of(4242L, () -> false));

    assertThat(status).isEqualTo("CANCELLED");
    verify(dbExecutorClient)
        .executeSql(eq(sql), eq(List.of("DELETE FROM \"GENIA_T5\".\"ORDERS\"")), eq(4242L));
    verify(executionRepository)
        .updateStepExecution(
            eq(STEP_EXEC_ID),
            eq("CANCELLED"),
            isNull(),
            isNull(),
            contains("출력 데이터는 바뀌지 않았습니다"),
            isNull(),
            any());
    verifyNoPgExecution();
  }

  /** UPDATE·DELETE 는 그대로 db-executor 로 간다(사용자 MERGE 는 검증기가 이미 거부). */
  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "UPDATE genia_t5.orders SET amount = 1 WHERE amount IS NULL",
        "DELETE FROM genia_t5.orders WHERE amount < 0",
      })
  void tiberoUserUpdateDelete_goToDbExecutorVerbatim(String sql) {
    stubExecuteSuccess();

    String status = run(sqlStep(sql, OUTPUT_ID, "APPEND"), false);

    assertThat(status).isEqualTo("COMPLETED");
    SentSql sent = captureSent();
    assertThat(sent.query()).isEqualTo(sql);
    assertThat(sent.preStatements()).isEmpty();
    verifyNoPgExecution();
  }

  // ── Review Focus 2: 로컬 모드에서도 PG 실행기를 건드리지 않는다 ─────────────────────────────

  @Test
  void tiberoSqlStep_localMode_neverTouchesPgExecutors() {
    // 로컬 모드(executorEnabled=false)는 PG 경로라면 SqlScriptExecutor 로 PG 에서 직접 실행한다 — Tibero 테넌트는 그 길로
    // 가면 안 된다(스펙 §2 실패 조건). SELECT 적재와 DML 둘 다 본다.
    stubProbe("AMOUNT", "NAME");
    stubOutputColumns(col("amount", false), col("name", false));
    stubExecuteSuccess();

    String selectStatus =
        run(sqlStep("SELECT amount, name FROM genia_t5.src_orders", OUTPUT_ID, "REPLACE"), false);
    String dmlStatus =
        run(sqlStep("DELETE FROM genia_t5.orders WHERE amount < 0", null, "APPEND"), false);

    assertThat(selectStatus).isEqualTo("COMPLETED");
    assertThat(dmlStatus).isEqualTo("COMPLETED");
    verifyNoInteractions(executorClient, sqlExecutor, sqlColumnProbe);
    // PG 검증기 빈도 쓰지 않는다 — 판정은 Tibero 검증기(GENIA_T5)로 한다.
    verifyNoInteractions(pgValidator);
  }

  // ── Review Focus 3: 대문자 probe 컬럼 ↔ 소문자 데이터셋 컬럼 ─────────────────────────────────

  @Test
  void tiberoSqlStep_upperCaseProbeColumns_matchLowerCaseDatasetColumns() {
    // Tibero 는 따옴표 없는 이름을 대문자로 접어 돌려준다. 시스템 컬럼(ID·_UPDATED_AT·IMPORT_ID)은 데이터셋 사용자 컬럼이 아니라
    // 대상에서 빠져야 한다(INSERT…SELECT 가 ID·_UPDATED_AT 을 스스로 채운다). 따옴표로 대소문자를 섞은 이름(Mixed)은 Tibero 가
    // 접지 않은 다른 이름이라 맞춤에 쓰지 않는다 — 출력 컬럼과 상관없는 이름이면 조용히 빠진다(출력 컬럼을 노린 경우는 아래 실패 테스트).
    String sql = "SELECT * FROM genia_t5.src_orders";
    stubProbe("ID", "AMOUNT", "NAME", "_UPDATED_AT", "IMPORT_ID", "Mixed");
    stubOutputColumns(col("amount", false), col("name", false));
    stubExecuteSuccess();

    String status = run(sqlStep(sql, OUTPUT_ID, "APPEND"), false);

    assertThat(status).isEqualTo("COMPLETED");
    assertThat(captureSent().query())
        .isEqualTo(
            TiberoPipelineSql.insertSelect(SCHEMA, OUTPUT_TABLE, List.of("amount", "name"), sql));
  }

  /**
   * F2 — 실행기는 probe 이름을 바꾸지 않고 보낸다. 따옴표 소문자 별칭({@code AS "amount"})은 Tibero 에서 소문자 이름 그대로라 적재 문장의
   * {@code src."AMOUNT"} 참조와 맞지 않는다. 그런 이름이 출력 컬럼과 맞으면 적재하지 않고 따옴표 없는 별칭을 쓰라고 안내한다.
   */
  @Test
  void tiberoSelect_quotedLowercaseAlias_failsWithGuidance() {
    stubProbe("ID_KEY", "amount");
    stubOutputColumns(col("id_key", false), col("amount", false));

    String status =
        run(
            sqlStep(
                "SELECT id_key, amount AS \"amount\" FROM genia_t5.src_orders",
                OUTPUT_ID,
                "APPEND"),
            false);

    assertThat(status).isEqualTo("FAILED");
    assertThat(failureMessage()).isEqualTo("Tibero 결과 컬럼 이름은 따옴표 없는 별칭으로 써 주세요(amount).");
    verify(dbExecutorClient, never()).executeSql(anyString(), anyList(), nullable(Long.class));
  }

  /** 따옴표 소문자 별칭만 있어도 일반 "일치하지 않음" 문구가 아니라 같은 안내를 준다(맞춤 계산보다 먼저 본다). */
  @Test
  void tiberoSelect_onlyQuotedLowercaseAlias_failsWithGuidanceNotGenericMismatch() {
    stubProbe("amount");
    stubOutputColumns(col("amount", false));

    String status = run(sqlStep("SELECT 1 AS \"amount\" FROM DUAL", OUTPUT_ID, "REPLACE"), false);

    assertThat(status).isEqualTo("FAILED");
    assertThat(failureMessage()).isEqualTo("Tibero 결과 컬럼 이름은 따옴표 없는 별칭으로 써 주세요(amount).");
    verify(dbExecutorClient, never()).executeSql(anyString(), anyList(), nullable(Long.class));
  }

  /** 대소문자 섞인 따옴표 별칭({@code AS "Amount"})도 출력 컬럼을 노린 이름이면 NULL 로 조용히 빠지지 않게 같은 안내로 실패한다. */
  @Test
  void tiberoSelect_mixedCaseQuotedAliasForOutputColumn_failsWithGuidance() {
    stubProbe("ID_KEY", "Amount");
    stubOutputColumns(col("id_key", false), col("amount", false));

    String status =
        run(
            sqlStep(
                "SELECT id_key, amount AS \"Amount\" FROM genia_t5.src_orders",
                OUTPUT_ID,
                "APPEND"),
            false);

    assertThat(status).isEqualTo("FAILED");
    assertThat(failureMessage()).isEqualTo("Tibero 결과 컬럼 이름은 따옴표 없는 별칭으로 써 주세요(Amount).");
    verify(dbExecutorClient, never()).executeSql(anyString(), anyList(), nullable(Long.class));
  }

  @Test
  void normalizeTiberoResultColumn_onlyFoldsAllUppercase() {
    assertThat(PipelineAsyncRunner.normalizeTiberoResultColumn("AMOUNT")).isEqualTo("amount");
    assertThat(PipelineAsyncRunner.normalizeTiberoResultColumn("_UPDATED_AT"))
        .isEqualTo("_updated_at");
    assertThat(PipelineAsyncRunner.normalizeTiberoResultColumn("amount")).isNull();
    assertThat(PipelineAsyncRunner.normalizeTiberoResultColumn("Mixed")).isNull();
  }

  @Test
  void tiberoSelect_noMatchingColumns_failsBeforeExecute() {
    stubProbe("OTHER");
    stubOutputColumns(col("amount", false));

    String status =
        run(sqlStep("SELECT other FROM genia_t5.src_orders", OUTPUT_ID, "APPEND"), false);

    assertThat(status).isEqualTo("FAILED");
    assertThat(failureMessage()).contains("SELECT 결과 컬럼이 출력 데이터셋의 컬럼과 일치하지 않습니다");
    verify(dbExecutorClient, never()).executeSql(anyString(), anyList(), nullable(Long.class));
  }

  // ── 증분 · 출력 없는 SELECT ────────────────────────────────────────────────

  // ── 실행기 실패 → 단계 FAILED + 번역 문구 ────────────────────────────────────────────────

  @Test
  void tiberoExecuteFailure_uniqueViolation_translatedLikePg() {
    stubProbe("AMOUNT");
    stubOutputColumns(col("amount", false));
    when(dbExecutorClient.executeSql(anyString(), anyList(), nullable(Long.class)))
        .thenReturn(
            failure(
                "JDBC-10007:UNIQUE constraint violation",
                new DbError(-10007, "JDBC-10007:UNIQUE constraint violation")));

    String status =
        run(sqlStep("SELECT amount FROM genia_t5.src_orders", OUTPUT_ID, "APPEND"), false);

    assertThat(status).isEqualTo("FAILED");
    assertThat(failureMessage())
        .startsWith("SQL 실행 실패: 같은 키 값이 이미 있습니다.")
        .endsWith("\n원인: JDBC-10007:UNIQUE constraint violation");
  }

  @Test
  void tiberoMergeFailure_sourceDuplicateKey_namesPkLikePg() {
    stubProbe("K", "V");
    stubOutputColumns(col("k", true), col("v", false));
    when(dbExecutorClient.executeSql(anyString(), anyList(), nullable(Long.class)))
        .thenReturn(
            failure(
                "JDBC-10021:Inconsistent set of rows in source tables.",
                new DbError(-10021, "JDBC-10021:Inconsistent set of rows in source tables.")));

    String status = run(sqlStep("SELECT k, v FROM genia_t5.src_orders", OUTPUT_ID, "MERGE"), false);

    assertThat(status).isEqualTo("FAILED");
    assertThat(failureMessage())
        .isEqualTo("SQL 결과에 같은 키(k)가 두 번 이상 나옵니다. 키별로 한 행만 나오도록 SQL을 수정하세요.");
  }

  @Test
  void tiberoProbeFailure_translatedWithProbePrefix_andNothingExecuted() {
    when(dbExecutorClient.probe(anyString()))
        .thenReturn(
            new ProbeResult(
                false,
                null,
                "JDBC-8033:Specified schema object was not found.",
                new DbError(-8033, "JDBC-8033:Specified schema object was not found.")));

    String status =
        run(sqlStep("SELECT amount FROM genia_t5.missing", OUTPUT_ID, "REPLACE"), false);

    assertThat(status).isEqualTo("FAILED");
    assertThat(failureMessage())
        .startsWith("SQL 컬럼 타입 분석 실패: SQL 에서 참조한 테이블이 없습니다.")
        .endsWith("\n원인: JDBC-8033:Specified schema object was not found.");
    verify(dbExecutorClient, never()).executeSql(anyString(), anyList(), nullable(Long.class));
  }

  /** 실행기에 닿지 못하면(주소 없음·연결 실패·401·시간 초과) 단계가 실패하고 PG 로 폴백하지 않는다(fail-closed). */
  @Test
  void tiberoDbExecutorUnreachable_stepFails_noPgFallback() {
    when(dbExecutorClient.executeSql(anyString(), anyList(), nullable(Long.class)))
        .thenThrow(new ScriptExecutionException(DbExecutorClient.CONNECT_FAILURE_MESSAGE));

    String status =
        run(sqlStep("UPDATE genia_t5.orders SET amount = 0", OUTPUT_ID, "APPEND"), false);

    assertThat(status).isEqualTo("FAILED");
    assertThat(failureMessage()).isEqualTo("Tibero 실행기에 연결하지 못했습니다.");
    verifyNoPgExecution();
  }

  // ── {{#N}} 참조 치환(Task 2 에서 미룬 러너 단위 테스트) ──────────────────────────────────────

  @Test
  void tiberoStepReference_resolvesToQuotedGeniaSchemaAndUpperTable() {
    PipelineStepResponse prev =
        step("SQL", "SELECT 1 FROM dual", 20L, "REPLACE", List.of(), 0, "prev");
    String sql = "DELETE FROM genia_t5.orders WHERE amount IN (SELECT amount FROM {{#1}})";
    PipelineStepResponse current = step("SQL", sql, null, "APPEND", List.of("prev"), 1, "cur");
    when(stepRepository.findByPipelineId(PIPELINE_ID)).thenReturn(List.of(prev, current));
    when(datasetRepository.findTableNameById(20L)).thenReturn(Optional.of("prev_out"));
    stubExecuteSuccess();

    String status = run(current, false);

    assertThat(status).isEqualTo("COMPLETED");
    // DML 은 그대로 보내므로 치환 결과를 그대로 볼 수 있다 — 스키마·테이블 모두 인용된 대문자 물리 이름.
    assertThat(captureSent().query())
        .isEqualTo(
            "DELETE FROM genia_t5.orders WHERE amount IN (SELECT amount FROM"
                + " \"GENIA_T5\".\"PREV_OUT\")");
  }

  // ── 도우미 ─────────────────────────────────────────────────────────────────────────

  private record SentSql(String query, List<String> preStatements) {}

  private String run(PipelineStepResponse step, boolean executorEnabled) {
    return runner.executeStep(STEP_EXEC_ID, step, PIPELINE_ID, "P", USER_ID, executorEnabled);
  }

  @SuppressWarnings("unchecked")
  private SentSql captureSent() {
    ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<List<String>> pre = ArgumentCaptor.forClass(List.class);
    verify(dbExecutorClient).executeSql(query.capture(), pre.capture(), nullable(Long.class));
    return new SentSql(query.getValue(), pre.getValue());
  }

  /** 단계 실패로 저장된 오류 문구. */
  private String failureMessage() {
    ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
    verify(executionRepository)
        .updateStepExecution(
            eq(STEP_EXEC_ID), eq("FAILED"), isNull(), isNull(), error.capture(), isNull(), any());
    return error.getValue();
  }

  /** PG 실행 경로(외부 실행기·로컬 실행기·로컬 probe) 어느 것도 쓰지 않았다. */
  private void verifyNoPgExecution() {
    verifyNoInteractions(executorClient, sqlExecutor, sqlColumnProbe);
  }

  private void stubProbe(String... names) {
    List<ProbeColumn> columns =
        Arrays.stream(names)
            .map(n -> new ProbeColumn(n, Types.VARCHAR, "VARCHAR", 100, 0))
            .toList();
    when(dbExecutorClient.probe(anyString()))
        .thenReturn(new ProbeResult(true, columns, null, null));
  }

  private void stubOutputColumns(DatasetColumnResponse... columns) {
    when(columnRepository.findByDatasetId(OUTPUT_ID)).thenReturn(List.of(columns));
  }

  private void stubExecuteSuccess() {
    when(dbExecutorClient.executeSql(anyString(), anyList(), nullable(Long.class)))
        .thenReturn(
            new SqlExecuteResult(true, List.of(), List.of(), 2, "2 rows", null, null, null));
  }

  private static SqlExecuteResult failure(String error, DbError dbError) {
    return new SqlExecuteResult(false, null, null, 0, "", error, null, dbError);
  }

  private static PipelineStepResponse sqlStep(String sql, Long outputDatasetId, String strategy) {
    return step("SQL", sql, outputDatasetId, strategy, List.of(), 0, "s1");
  }

  private static PipelineStepResponse step(
      String scriptType,
      String content,
      Long outputDatasetId,
      String strategy,
      List<String> dependsOn,
      int order,
      String name) {
    return new PipelineStepResponse(
        100L + order,
        name,
        null,
        scriptType,
        content,
        outputDatasetId,
        null,
        List.of(),
        dependsOn,
        order,
        strategy,
        null,
        null,
        null,
        null);
  }

  private static DatasetColumnResponse col(String columnName, boolean isPrimaryKey) {
    return new DatasetColumnResponse(
        null, columnName, null, "TEXT", null, true, false, null, 0, isPrimaryKey);
  }
}
