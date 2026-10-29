package com.smartfirehub.pipeline.service.step;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.dataset.storage.TenantDataStoreRepository;
import com.smartfirehub.dataset.storage.tibero.TiberoPipelineSql;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.exception.ScriptExecutionException;
import com.smartfirehub.pipeline.service.ColumnInfo;
import com.smartfirehub.pipeline.service.IncrementalCursorService;
import com.smartfirehub.pipeline.service.LocalPythonOutputLoader;
import com.smartfirehub.pipeline.service.MergeSqlBuilder;
import com.smartfirehub.pipeline.service.OutputClearStatement;
import com.smartfirehub.pipeline.service.PipelineDataSchema;
import com.smartfirehub.pipeline.service.PipelineSqlErrorMessages;
import com.smartfirehub.pipeline.service.PythonScriptExecutor;
import com.smartfirehub.pipeline.service.TiberoIncrementalCursor;
import com.smartfirehub.pipeline.service.executor.DbExecutorClient;
import com.smartfirehub.pipeline.service.executor.ExecutionCancelCheck;
import com.smartfirehub.pipeline.service.executor.ExecutorClient;
import com.smartfirehub.pipeline.service.executor.ScriptTokenIssuer;
import com.smartfirehub.pipeline.service.validator.SqlValidator;
import java.sql.Types;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * {@link StepDialect} 두 구현(PG·Tibero)의 분기 계약 단위 테스트(WD-231). 협력 객체는 모두 목이다 — 방언이 러너에서 옮겨 온 분기를 그대로
 * 지키는지(어느 협력 객체를 부르고 어느 것을 부르지 않는지)만 본다. 러너 통합 동작은 기존 {@code PipelineAsyncRunner*Test} 가 맡는다.
 */
class StepDialectTest {

  private static final long TENANT_ID = 7L;

  private IncrementalCursorService incrementalCursorService;
  private PythonScriptExecutor pythonExecutor;
  private LocalPythonOutputLoader localPythonOutputLoader;
  private PipelineDataSchema pipelineDataSchema;
  private DataTableService dataTableService;
  private TiberoIncrementalCursor tiberoIncrementalCursor;
  private DbExecutorClient dbExecutorClient;
  private ScriptTokenIssuer scriptTokenIssuer;
  private TenantDataStoreRepository tenantDataStoreRepository;
  private SqlValidator validator;
  private DatasetColumnRepository columnRepository;

  private PgStepDialect pg;
  private TiberoStepDialect tibero;

  @BeforeEach
  void setUp() {
    incrementalCursorService = mock(IncrementalCursorService.class);
    pythonExecutor = mock(PythonScriptExecutor.class);
    localPythonOutputLoader = mock(LocalPythonOutputLoader.class);
    pipelineDataSchema = mock(PipelineDataSchema.class);
    dataTableService = mock(DataTableService.class);
    tiberoIncrementalCursor = mock(TiberoIncrementalCursor.class);
    dbExecutorClient = mock(DbExecutorClient.class);
    scriptTokenIssuer = mock(ScriptTokenIssuer.class);
    tenantDataStoreRepository = mock(TenantDataStoreRepository.class);
    validator = mock(SqlValidator.class);
    columnRepository = mock(DatasetColumnRepository.class);
    when(pipelineDataSchema.validator()).thenReturn(validator);
    when(pipelineDataSchema.current()).thenReturn("GENIA_T7");

    pg =
        new PgStepDialect(
            incrementalCursorService, pythonExecutor, localPythonOutputLoader, columnRepository);
    tibero =
        new TiberoStepDialect(
            pipelineDataSchema,
            dataTableService,
            tiberoIncrementalCursor,
            dbExecutorClient,
            scriptTokenIssuer,
            tenantDataStoreRepository,
            pythonExecutor,
            localPythonOutputLoader,
            columnRepository);
    // PG 방언의 비우기 문장(OutputClearStatement)·래핑 SQL(DataSchema.qualify)은 테넌트 data 스키마
    // 이름을 TenantContext 에서 풀어 낸다 — 없으면 MissingTenantScopeException 이다(Tibero 쪽은 목
    // PipelineDataSchema 라 무관).
    TenantContext.set(TENANT_ID);
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  @Test
  void pg_takesOutputSessionLock_onlyForReplace() {
    assertThat(pg.takesOutputSessionLock(true)).isTrue();
    assertThat(pg.takesOutputSessionLock(false)).isFalse();
    // Tibero 는 REPLACE 여도 PG 출력 세션 잠금을 잡지 않는다 — 잠금의 목적이 Tibero 에 없다.
    assertThat(tibero.takesOutputSessionLock(true)).isFalse();
    assertThat(tibero.takesOutputSessionLock(false)).isFalse();
  }

  @Test
  void stagesAppend_onlyTiberoNonReplaceWithOutput() {
    assertThat(tibero.stagesAppend(false, "t")).isTrue();
    assertThat(tibero.stagesAppend(true, "t")).isFalse();
    assertThat(tibero.stagesAppend(false, null)).isFalse();
    assertThat(pg.stagesAppend(false, "t")).isFalse();
    assertThat(pg.stagesAppend(true, "t")).isFalse();
    assertThat(pg.stagesAppend(false, null)).isFalse();
  }

  @Test
  void pg_issueScriptToken_isNull_andTouchesNothing() {
    String token = pg.issueScriptToken(TENANT_ID, 3, Duration.ofSeconds(60));

    assertThat(token).isNull();
    verifyNoInteractions(
        scriptTokenIssuer,
        tenantDataStoreRepository,
        incrementalCursorService,
        pythonExecutor,
        localPythonOutputLoader);
  }

  @Test
  void tibero_issueScriptToken_readsEpochThenIssues() {
    Duration ttl = Duration.ofSeconds(90);
    when(tenantDataStoreRepository.findScriptTokenEpoch(TENANT_ID))
        .thenReturn(java.util.Optional.of(5L));
    when(scriptTokenIssuer.issue(TENANT_ID, 3, 5L, ttl)).thenReturn("tok");

    assertThat(tibero.issueScriptToken(TENANT_ID, 3, ttl)).isEqualTo("tok");
  }

  @Test
  void prepareSqlExecution_pgIsNoOp_tiberoEnsuresTriggers() {
    // PG: 어떤 인자 조합에서도 데이터 테이블 서비스·검증기에 손대지 않는다.
    pg.prepareSqlExecution("INSERT INTO t VALUES (1)", false, true, "out");
    verifyNoInteractions(dataTableService, validator);

    // Tibero 사용자 DML(INSERT): 쓰기 대상 테이블에 행 트리거를 보장한다.
    when(validator.referencedTables("INSERT INTO t VALUES (1)"))
        .thenReturn(
            new SqlValidator.ReferencedTables(
                Set.of(), Set.of(new SqlValidator.TableName("GENIA_T7", "t"))));
    tibero.prepareSqlExecution("INSERT INTO t VALUES (1)", false, false, null);
    verify(dataTableService).ensureRowTriggers(List.of("t"));
  }

  @Test
  void tibero_prepareSqlExecution_selectNonIncrementalDoesNothing() {
    tibero.prepareSqlExecution("SELECT 1 FROM DUAL", true, false, "out");

    verifyNoInteractions(dataTableService);
  }

  @Test
  void tibero_prepareSqlExecution_incrementalEnsuresOutputAndInputTriggers() {
    String sql = "SELECT * FROM src";
    when(validator.referencedTables(sql))
        .thenReturn(
            new SqlValidator.ReferencedTables(
                Set.of(new SqlValidator.TableName("GENIA_T7", "src")), Set.of()));

    tibero.prepareSqlExecution(sql, true, true, "out");

    // 출력 → 입력 순서, 물리(대문자) 이름으로 한 번에 보장한다.
    verify(dataTableService).ensureRowTriggers(List.of("OUT", "SRC"));
  }

  @Test
  void tibero_prepareSqlExecution_updateTriggerFailureIsSwallowed() {
    String sql = "UPDATE t SET a = 1";
    when(validator.referencedTables(sql))
        .thenReturn(
            new SqlValidator.ReferencedTables(
                Set.of(), Set.of(new SqlValidator.TableName("GENIA_T7", "t"))));
    doThrow(new RuntimeException("no grant")).when(dataTableService).ensureRowTriggers(anyList());

    // UPDATE 는 이전부터 허용되던 문장이라 트리거를 못 만들어도 막지 않는다.
    tibero.prepareSqlExecution(sql, false, false, null);

    verify(dataTableService).ensureRowTriggers(List.of("t"));
  }

  @Test
  void tibero_prepareSqlExecution_deleteNeedsNoTrigger() {
    tibero.prepareSqlExecution("DELETE FROM t", false, false, null);

    verify(dataTableService, never()).ensureRowTriggers(any());
  }

  @Test
  void captureIncrementalCandidate_routesByBackend() {
    OffsetDateTime pgNow = OffsetDateTime.parse("2026-01-01T00:00:00Z");
    OffsetDateTime tiberoNow = OffsetDateTime.parse("2026-02-02T00:00:00Z");
    when(incrementalCursorService.captureCandidate()).thenReturn(pgNow);
    when(tiberoIncrementalCursor.captureCandidate()).thenReturn(tiberoNow);

    assertThat(pg.captureIncrementalCandidate()).isEqualTo(pgNow);
    verifyNoInteractions(tiberoIncrementalCursor);

    assertThat(tibero.captureIncrementalCandidate()).isEqualTo(tiberoNow);
    // Tibero 호출이 PG 후보 함수를 더 부르지 않았다(PG 호출은 위의 1회뿐).
    verify(incrementalCursorService).captureCandidate();
    verifyNoMoreInteractions(incrementalCursorService);
  }

  @Test
  void outputClearStatement_matchesExisting() {
    assertThat(pg.outputClearStatement("out_t")).isEqualTo(OutputClearStatement.deleteAll("out_t"));
    assertThat(tibero.outputClearStatement("out_t"))
        .isEqualTo(TiberoPipelineSql.deleteAll("GENIA_T7", "out_t"));
  }

  @Test
  void runLocalPython_pgUsesSlotRole_tiberoUsesScriptToken() {
    PythonScriptExecutor.RunResult pgRun = new PythonScriptExecutor.RunResult(0, "o", "e");
    PythonScriptExecutor.RunResult tiberoRun = new PythonScriptExecutor.RunResult(0, "O", "E");
    when(pythonExecutor.run("print(1)", 4)).thenReturn(pgRun);
    when(pythonExecutor.runTibero("print(1)", "tok")).thenReturn(tiberoRun);

    assertThat(pg.runLocalPython("print(1)", 4, "ignored")).isSameAs(pgRun);
    assertThat(tibero.runLocalPython("print(1)", 4, "tok")).isSameAs(tiberoRun);
  }

  @Test
  void loadLocalPythonOutput_pgLoadsInTransaction_tiberoLoadsIntoStaging() {
    Map<String, String> types = Map.of("a", "TEXT");
    when(localPythonOutputLoader.load("tbl", "{}", types)).thenReturn(3L);
    when(localPythonOutputLoader.loadStaging("stg", "{}", types)).thenReturn(5L);

    assertThat(pg.loadLocalPythonOutput("tbl", "{}", types)).isEqualTo(3L);
    verify(localPythonOutputLoader, never()).loadStaging(any(), any(), any());

    // PG 호출 기록을 지운 뒤, Tibero 가 트랜잭션 적재(load)를 어떤 인자로도 부르지 않는지 본다.
    clearInvocations(localPythonOutputLoader);
    assertThat(tibero.loadLocalPythonOutput("stg", "{}", types)).isEqualTo(5L);
    verify(localPythonOutputLoader, never()).load(any(), any(), any());
  }

  @Test
  void tibero_probeTibero_returnsSuccessAndTranslatesFailure() {
    DbExecutorClient.ProbeResult ok = new DbExecutorClient.ProbeResult(true, List.of(), null, null);
    when(dbExecutorClient.probe("SELECT 1")).thenReturn(ok);
    assertThat(tibero.probeTibero("SELECT 1")).isSameAs(ok);

    DbExecutorClient.ProbeResult bad =
        new DbExecutorClient.ProbeResult(false, List.of(), "연결 실패", null);
    when(dbExecutorClient.probe("SELECT 2")).thenReturn(bad);
    assertThatThrownBy(() -> tibero.probeTibero("SELECT 2"))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessageStartingWith(PipelineSqlErrorMessages.PROBE_PREFIX);
  }

  @Test
  void tibero_tiberoResultColumns_foldsUppercaseAndRejectsQuotedNames() {
    DbExecutorClient.ProbeResult probe =
        new DbExecutorClient.ProbeResult(
            true,
            List.of(new DbExecutorClient.ProbeColumn("AMOUNT", 12, "VARCHAR2", null, null)),
            null,
            null);
    assertThat(tibero.tiberoResultColumns(probe))
        .extracting(ColumnInfo::name, ColumnInfo::appType)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("amount", "TEXT"));

    DbExecutorClient.ProbeResult quoted =
        new DbExecutorClient.ProbeResult(
            true,
            List.of(new DbExecutorClient.ProbeColumn("Mixed", 12, "VARCHAR2", null, null)),
            null,
            null);
    assertThatThrownBy(() -> tibero.tiberoResultColumns(quoted))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessageStartingWith(TiberoStepDialect.QUOTED_RESULT_COLUMN_PREFIX);
    assertThat(TiberoStepDialect.normalizeTiberoResultColumn("AMOUNT")).isEqualTo("amount");
    assertThat(TiberoStepDialect.normalizeTiberoResultColumn("Mixed")).isNull();
  }

  @Test
  void tibero_prepareSqlExecution_insertTriggerFailurePropagates() {
    String sql = "INSERT INTO t VALUES (1)";
    when(validator.referencedTables(sql))
        .thenReturn(
            new SqlValidator.ReferencedTables(
                Set.of(), Set.of(new SqlValidator.TableName("GENIA_T7", "t"))));
    RuntimeException denied = new RuntimeException("no grant");
    doThrow(denied).when(dataTableService).ensureRowTriggers(anyList());

    // INSERT 는 트리거가 ID 를 채우므로 못 만들면 실행 전에 실패한다(fail-closed) — UPDATE 처럼 삼키지 않는다.
    assertThatThrownBy(() -> tibero.prepareSqlExecution(sql, false, false, null)).isSameAs(denied);
  }

  // ── Tibero SQL 실행 본체(executeSql) ─────────────────────────────────────────────

  @Test
  void tibero_executeSql_dmlSendsUserSqlVerbatimWithPreStatements() {
    stubDbExecuteSuccess();
    List<String> pre = List.of(TiberoPipelineSql.deleteAll("GENIA_T7", OUT_TABLE));

    String log =
        tibero.executeSql(
            new SqlStepRun("UPDATE t SET a = 1", pre, false, OUT_TABLE, OUT_ID, false, false, null),
            NO_CANCEL);

    assertThat(log).isEqualTo("ok");
    verify(dbExecutorClient).executeSql("UPDATE t SET a = 1", pre);
    // 사용자 DML 은 열 탐침·출력 컬럼 조회가 필요 없다.
    verify(dbExecutorClient, never()).probe(anyString());
    verifyNoInteractions(columnRepository);
  }

  @Test
  void tibero_executeSql_selectProbesThenSendsInsertSelect() {
    when(dbExecutorClient.probe("SELECT a FROM s")).thenReturn(probe("A"));
    when(columnRepository.findByDatasetId(OUT_ID)).thenReturn(List.of(col("a", false)));
    stubDbExecuteSuccess();

    tibero.executeSql(
        new SqlStepRun("SELECT a FROM s", List.of(), true, OUT_TABLE, OUT_ID, false, false, null),
        NO_CANCEL);

    verify(dbExecutorClient)
        .executeSql(
            TiberoPipelineSql.insertSelect(
                "GENIA_T7", OUT_TABLE, List.of("a"), List.of("A"), "SELECT a FROM s"),
            List.of());
  }

  @Test
  void tibero_executeSql_reusesProbeFromTempCreation() {
    when(columnRepository.findByDatasetId(OUT_ID)).thenReturn(List.of(col("a", false)));
    stubDbExecuteSuccess();

    tibero.executeSql(
        new SqlStepRun(
            "SELECT a FROM s", List.of(), true, OUT_TABLE, OUT_ID, false, true, probe("A")),
        NO_CANCEL);

    // TEMP 생성 때 탐지한 결과를 다시 쓴다 — 탐침을 두 번 보내지 않는다.
    verify(dbExecutorClient, never()).probe(anyString());
    verify(dbExecutorClient).executeSql(anyString(), eq(List.of()));
  }

  @Test
  void tibero_executeSql_mergeKeysOnDatasetPk() {
    when(dbExecutorClient.probe("SELECT k, v FROM s")).thenReturn(probe("K", "V"));
    when(columnRepository.findByDatasetId(OUT_ID))
        .thenReturn(List.of(col("k", true), col("v", false)));
    stubDbExecuteSuccess();

    tibero.executeSql(
        new SqlStepRun("SELECT k, v FROM s", List.of(), true, OUT_TABLE, OUT_ID, true, false, null),
        NO_CANCEL);

    verify(dbExecutorClient)
        .executeSql(
            TiberoPipelineSql.mergeSelect(
                "GENIA_T7", OUT_TABLE, List.of("k", "v"), List.of("k"), "SELECT k, v FROM s"),
            List.of());
  }

  @Test
  void tibero_executeSql_selectWithoutOutputTableFailsWithoutExecuting() {
    SqlStepRun run =
        new SqlStepRun("SELECT 1 FROM DUAL", List.of(), true, null, OUT_ID, false, false, null);

    assertThatThrownBy(() -> tibero.executeSql(run, NO_CANCEL))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessage("출력 데이터셋의 테이블을 찾을 수 없습니다.");
    verifyNoInteractions(dbExecutorClient);
  }

  @Test
  void tibero_executeSql_failureTranslatedWithExecutionPrefix() {
    when(dbExecutorClient.executeSql(anyString(), anyList()))
        .thenReturn(
            new ExecutorClient.SqlExecuteResult(
                false, List.of(), List.of(), 0, null, "boom", null, null));
    SqlStepRun run =
        new SqlStepRun("DELETE FROM t", List.of(), false, null, null, false, false, null);

    assertThatThrownBy(() -> tibero.executeSql(run, NO_CANCEL))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessageStartingWith(PipelineSqlErrorMessages.EXECUTION_PREFIX);
  }

  @Test
  void tibero_executeSql_cancelCheckpointBeforeExecuting() {
    ExecutionCancelCheck cancel = mock(ExecutionCancelCheck.class);
    stubDbExecuteSuccess();

    tibero.executeSql(
        new SqlStepRun("DELETE FROM t", List.of(), false, null, null, false, false, null), cancel);

    // 취소 확인은 실행 요청 직전 — 아직 출력에 아무것도 쓰지 않았다.
    InOrder order = inOrder(cancel, dbExecutorClient);
    order.verify(cancel).checkpoint(0, false);
    order.verify(dbExecutorClient).executeSql("DELETE FROM t", List.of());
  }

  // ── PG SQL 래핑·실패 번역(싱크 호출 자체는 보안 불변식 때문에 러너 executeStep 에 남는다) ─────────────

  @Test
  void pg_wrapSelect_insertIntoMatchedColumnsOnly() {
    when(columnRepository.findByDatasetId(OUT_ID))
        .thenReturn(List.of(col("a", false), col("b", false)));
    SqlStepRun run =
        new SqlStepRun(
            "SELECT a, z FROM s", List.of(), true, OUT_TABLE, OUT_ID, false, false, null);

    PgStepDialect.WrappedSelect wrapped =
        pg.wrapSelect(
            run, List.of(new ColumnInfo("a", "TEXT"), new ColumnInfo("z", "TEXT")), false);

    assertThat(wrapped.sql())
        .isEqualTo("INSERT INTO " + DataSchema.qualify(OUT_TABLE) + " (\"a\") SELECT a, z FROM s");
    assertThat(wrapped.mergePkColumns()).isNull();
  }

  @Test
  void pg_wrapSelect_mergeUsesDatasetPk() {
    when(columnRepository.findByDatasetId(OUT_ID))
        .thenReturn(List.of(col("k", true), col("v", false)));
    SqlStepRun run =
        new SqlStepRun("SELECT k, v FROM s", List.of(), true, OUT_TABLE, OUT_ID, true, false, null);

    PgStepDialect.WrappedSelect wrapped =
        pg.wrapSelect(
            run, List.of(new ColumnInfo("k", "TEXT"), new ColumnInfo("v", "TEXT")), false);

    assertThat(wrapped.sql())
        .isEqualTo(
            MergeSqlBuilder.build(
                DataSchema.qualify(OUT_TABLE),
                List.of("k", "v"),
                List.of("k"),
                "SELECT k, v FROM s"));
    assertThat(wrapped.mergePkColumns()).containsExactly("k");
  }

  @Test
  void pg_wrapSelect_autoCreatedTempRenamesReservedColumns() {
    when(columnRepository.findByDatasetId(OUT_ID))
        .thenReturn(List.of(col("id_1", false), col("v", false)));
    SqlStepRun run =
        new SqlStepRun("SELECT * FROM s", List.of(), true, OUT_TABLE, OUT_ID, false, true, null);

    PgStepDialect.WrappedSelect wrapped =
        pg.wrapSelect(
            run, List.of(new ColumnInfo("id", "BIGINT"), new ColumnInfo("v", "TEXT")), false);

    // 러너가 만든 TEMP 는 예약어 별칭(id → id_1)으로 만들어졌으므로 적재 대상도 같은 별칭이다.
    assertThat(wrapped.sql())
        .isEqualTo(
            "INSERT INTO " + DataSchema.qualify(OUT_TABLE) + " (\"id_1\", \"v\") SELECT * FROM s");
  }

  @Test
  void pg_wrapSelect_columnMatchCheckedBeforeMergePk() {
    when(columnRepository.findByDatasetId(OUT_ID)).thenReturn(List.of(col("v", false)));
    SqlStepRun noMatch =
        new SqlStepRun("SELECT z FROM s", List.of(), true, OUT_TABLE, OUT_ID, true, false, null);
    SqlStepRun noPk =
        new SqlStepRun("SELECT v FROM s", List.of(), true, OUT_TABLE, OUT_ID, true, false, null);

    // 열 맞춤이 먼저다 — 맞는 열이 없으면 PK 안내가 아니라 열 불일치 안내로 실패한다.
    assertThatThrownBy(() -> pg.wrapSelect(noMatch, List.of(new ColumnInfo("z", "TEXT")), false))
        .hasMessageStartingWith("SELECT 결과 컬럼이 출력 데이터셋의 컬럼과 일치하지 않습니다.");
    assertThatThrownBy(() -> pg.wrapSelect(noPk, List.of(new ColumnInfo("v", "TEXT")), false))
        .hasMessageStartingWith("MERGE 에는 출력 데이터셋 PK 컬럼이 필요합니다");
  }

  @Test
  void pg_translateExecutorFailure_usesExecutionPrefixWhenNotMerge() {
    ScriptExecutionException e = pg.translateExecutorFailure(null, "boom", null);

    assertThat(e.getMessage())
        .isEqualTo(
            PipelineSqlErrorMessages.describeExecutorError(
                PipelineSqlErrorMessages.EXECUTION_PREFIX, null, "boom"));
  }

  @Test
  void pg_translateLocalFailure_returnsSameExceptionWhenNothingToTranslate() {
    ScriptExecutionException original = new ScriptExecutionException("raw");

    // MERGE 가 아니면 번역하지 않고 같은 예외를 그대로 돌려준다(예전 rethrow 와 같다).
    assertThat(pg.translateLocalFailure(original, null)).isSameAs(original);
    // MERGE 여도 번역할 오류(원본 키 중복 등)가 아니면 같은 예외다.
    assertThat(pg.translateLocalFailure(original, List.of("k"))).isSameAs(original);
  }

  /** MERGE 의 같은 키 중복 오류는 번역된 새 예외로 바뀌되, 원인은 원래 예외로 남는다 — 로그·스택에서 원본 오류(PG 서버 메시지)를 따라갈 수 있어야 한다. */
  @Test
  void pg_translateLocalFailure_keepsOriginalAsCauseWhenTranslated() {
    ScriptExecutionException original =
        new ScriptExecutionException("ERROR: " + MergeSqlBuilder.DUPLICATE_KEY_PG_MESSAGE);

    ScriptExecutionException translated = pg.translateLocalFailure(original, List.of("k"));

    assertThat(translated).isNotSameAs(original);
    assertThat(translated.getMessage()).contains("같은 키(k)");
    assertThat(translated).hasCause(original);
  }

  private static final String OUT_TABLE = "orders";
  private static final Long OUT_ID = 10L;

  /** 취소 요청이 없는 확인기 — checkpoint 기본 구현이 아무것도 던지지 않는다. */
  private static final ExecutionCancelCheck NO_CANCEL = () -> false;

  private void stubDbExecuteSuccess() {
    when(dbExecutorClient.executeSql(anyString(), anyList()))
        .thenReturn(
            new ExecutorClient.SqlExecuteResult(
                true, List.of(), List.of(), 1, "ok", null, null, null));
  }

  private static DbExecutorClient.ProbeResult probe(String... names) {
    return new DbExecutorClient.ProbeResult(
        true,
        Arrays.stream(names)
            .map(n -> new DbExecutorClient.ProbeColumn(n, Types.VARCHAR, "VARCHAR", 100, 0))
            .toList(),
        null,
        null);
  }

  private static DatasetColumnResponse col(String name, boolean pk) {
    return new DatasetColumnResponse(null, name, null, "TEXT", null, true, false, null, 0, pk);
  }
}
