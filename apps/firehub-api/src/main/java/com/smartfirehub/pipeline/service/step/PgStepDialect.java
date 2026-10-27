package com.smartfirehub.pipeline.service.step;

import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.pipeline.exception.ScriptExecutionException;
import com.smartfirehub.pipeline.service.ColumnInfo;
import com.smartfirehub.pipeline.service.IncrementalCursorService;
import com.smartfirehub.pipeline.service.LocalPythonOutputLoader;
import com.smartfirehub.pipeline.service.MergeSqlBuilder;
import com.smartfirehub.pipeline.service.OutputClearStatement;
import com.smartfirehub.pipeline.service.PipelineSqlErrorMessages;
import com.smartfirehub.pipeline.service.PythonScriptExecutor;
import com.smartfirehub.pipeline.service.executor.ExecutorClient;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * PostgreSQL 테넌트의 스텝 실행 방언(WD-231). 러너가 예전에 {@code tiberoTenant} 불리언으로 가르던 PG 쪽 분기 본문을 그대로 옮겼다 — 동작
 * 변화 없음.
 *
 * <p>PG 는 대부분 「아무것도 안 한다」다: 행 트리거 보장·스크립트 토큰·APPEND 스테이징이 모두 Tibero 전용이다.
 *
 * <p><b>SQL 실행은 반만 맡는다.</b> PG 싱크({@code ExecutorClient.executeSql}·{@code
 * SqlScriptExecutor.execute}·{@code SqlColumnProbe.columnsWithTypes}) 호출은 {@code
 * SqlGateArchitectureTest} 가 보안 판정({@code checkStepSqlForRun})과 같은 메서드(러너 {@code executeStep})에 두도록
 * 강제한다. 그래서 이 방언은 싱크가 아닌 부분 — SELECT 래핑 SQL 조립({@link #wrapSelect})과 실패 번역({@link
 * #translateExecutorFailure}·{@link #translateLocalFailure}) — 만 맡고, 러너가 그 사이에서 싱크를 직접 부른다.
 */
public class PgStepDialect implements StepDialect {

  private final IncrementalCursorService incrementalCursorService;
  private final PythonScriptExecutor pythonExecutor;
  private final LocalPythonOutputLoader localPythonOutputLoader;
  private final DatasetColumnRepository columnRepository;

  public PgStepDialect(
      IncrementalCursorService incrementalCursorService,
      PythonScriptExecutor pythonExecutor,
      LocalPythonOutputLoader localPythonOutputLoader,
      DatasetColumnRepository columnRepository) {
    this.incrementalCursorService = incrementalCursorService;
    this.pythonExecutor = pythonExecutor;
    this.localPythonOutputLoader = localPythonOutputLoader;
    this.columnRepository = columnRepository;
  }

  /**
   * PG SELECT 자동 적재의 래핑 결과.
   *
   * @param sql 실행할 문장({@code INSERT INTO ... SELECT} 또는 {@link MergeSqlBuilder} 의 MERGE)
   * @param mergePkColumns MERGE 면 병합 키(출력 PK, 실패 번역에 쓴다), 아니면 null
   */
  public record WrappedSelect(String sql, List<String> mergePkColumns) {}

  /**
   * SELECT 자동 적재: 결과 컬럼을 출력 데이터셋 컬럼과 맞춘 뒤 {@code INSERT INTO ... SELECT}(또는 MERGE)로 감싼다. 싱크(탐침·실행)는
   * 부르지 않는다 — 탐침 결과는 러너가 판정 메서드 안에서 얻어 넘긴다.
   *
   * @param run 실행 입력(sql·출력 테이블/데이터셋·MERGE 여부·자동 생성 TEMP 여부를 쓴다)
   * @param probedColumns 러너가 {@code SqlColumnProbe} 로 얻은 결과 컬럼(null 아님)
   */
  public WrappedSelect wrapSelect(SqlStepRun run, List<ColumnInfo> probedColumns) {
    // 이번 실행에서 임시 데이터셋을 자동 생성/재사용한 경우에만 TEMP 생성 때와 동일한 규칙으로 예약어
    // 컬럼명을 별칭 처리한다 — 임시 데이터셋에 실제로 저장된 컬럼명(id_1 등)과 정확히
    // 매칭되어야 하기 때문이다. 사용자가 직접 지정한 기존 출력 데이터셋에는 이 별칭 처리를
    // 적용하지 않는다 — 그 데이터셋의 "id" 컬럼은 예약어 충돌이 아니라 사용자가 실제로
    // 만든 정상 컬럼일 수 있다(#645).
    List<String> rawSelectColumns = probedColumns.stream().map(ColumnInfo::name).toList();
    List<String> selectColumns =
        run.tempDatasetAutoCreated()
            ? SqlOutputColumns.renameReservedColumnNames(rawSelectColumns)
            : rawSelectColumns;

    // 출력 데이터셋의 컬럼은 **하나도 빼지 않는다**. 특히 is_primary_key 컬럼을 제외하면 안 된다
    // (#684). 이 경로는 대상 목록만 좁히고 SELECT 식 목록은 그대로 두기 때문에, 대상에서 한
    // 컬럼이라도 빠지면 식 개수가 어긋나 PostgreSQL 이 INSERT has more expressions than target
    // columns 로 거부한다 — 2026-09-17 운영 장애가 정확히 이것이다.
    //
    // 예전에는 여기서 !col.isPrimaryKey() 로 걸렀다. "PK 는 시스템이 채우는 대리키"라는 가정이
    // 었는데 이 스키마에서는 성립하지 않는다:
    //  - 물리 PK 는 DataTableService 가 id BIGSERIAL PRIMARY KEY 로 **따로** 만든다.
    //  - id/import_id/created_at 은 시스템 예약어라 dataset_column 에 애초에 들어올 수 없다.
    //  - is_primary_key 가 붙은 컬럼은 평범한 NOT NULL 컬럼 + PK 유니크 인덱스(이름은
    //    ux_<table>_pk, REPLACE·긴 이름이면 다를 수 있다 — WD-103)일 뿐이고 기본값도 없다
    //    — 즉 대리키가 아니라 **사용자가 값을 넣어야 하는 업무 키**다.
    // 그래서 빼면 개수 불일치로 실패하고, SELECT 에서도 빼면 이번엔 NOT NULL 위반이 난다.
    // 어느 쪽으로도 성공할 수 없었다.
    // 출력 데이터셋 컬럼 메타데이터는 여기서 한 번만 읽는다 — 아래 MERGE 분기의 PK 목록도 같은
    // 목록에서 뽑는다(같은 스텝 실행 안에서 두 번 읽을 이유가 없다).
    List<DatasetColumnResponse> outputColumns =
        columnRepository.findByDatasetId(run.outputDatasetId());
    List<String> matchedColumns =
        SqlOutputColumns.matchOutputColumns(
            selectColumns, selectColumns, SqlOutputColumns.outputColumnNames(outputColumns));

    // 출력 테이블은 현재 테넌트의 데이터 스키마에 있다 — 스키마명을 직접 적지 않고
    // DataSchema.qualify 로 조립한다(테이블명 인용·따옴표 이중화까지 그쪽이 책임진다).
    String outputTableName = run.outputTableName();
    if (run.isMerge()) {
      // MERGE 는 출력 데이터셋의 기존 PK(PK 유니크 인덱스 — 이름은 ux_<table>_pk 이거나
      // REPLACE·긴 이름이면 다를 수 있다, dataset_column.is_primary_key 로 기록됨)를 그대로
      // 재사용한다 — 사용자가 별도로 병합 키를 고르지 않는다. 저장 시점
      // (PipelineService.saveSteps)에 PK 존재가 이미 검증됐지만, 저장 이후 데이터셋 스키마가
      // 바뀌었을 수 있어(컬럼 삭제 등) 실행 시점에도 다시 확인한다 — 조용히 APPEND 로 격하되면
      // 안 되므로 여기서 명확히 실패시킨다.
      List<String> mergePkColumns = SqlOutputColumns.requireMergePk(outputColumns);
      return new WrappedSelect(
          MergeSqlBuilder.build(
              DataSchema.qualify(outputTableName), matchedColumns, mergePkColumns, run.sql()),
          mergePkColumns);
    }
    String columnList =
        matchedColumns.stream().map(col -> "\"" + col + "\"").collect(Collectors.joining(", "));
    return new WrappedSelect(
        "INSERT INTO " + DataSchema.qualify(outputTableName) + " (" + columnList + ") " + run.sql(),
        null);
  }

  /**
   * 실행기(firehub-executor)가 돌려준 실패를 단계 오류로 바꾼다. MERGE 면 원본 키 중복 등을 PK 이름으로 안내하는 번역을 먼저 시도하고, 해당이 없거나
   * MERGE 가 아니면 공통 실행 실패 문구({@link PipelineSqlErrorMessages#EXECUTION_PREFIX})다. 원인 예외는 없다(실행기
   * 응답이라).
   *
   * @param mergePkColumns MERGE 면 병합 키, 아니면 null
   */
  public ScriptExecutionException translateExecutorFailure(
      ExecutorClient.PgError pgError, String error, List<String> mergePkColumns) {
    String translated = mergeTranslation(pgError, error, mergePkColumns);
    if (translated != null) {
      return new ScriptExecutionException(translated);
    }
    return new ScriptExecutionException(
        PipelineSqlErrorMessages.describeExecutorError(
            PipelineSqlErrorMessages.EXECUTION_PREFIX, pgError, error));
  }

  /**
   * 로컬 실행({@code SqlScriptExecutor})이 던진 실패를 단계 오류로 바꾼다. MERGE 번역이 되면 원래 예외를 원인으로 단 새 예외를, 아니면
   * <b>같은 예외</b>를 돌려준다 — 예전 {@code throw e} 와 같게 예외 객체·문구를 바꾸지 않는다.
   *
   * @param mergePkColumns MERGE 면 병합 키, 아니면 null
   */
  public ScriptExecutionException translateLocalFailure(
      ScriptExecutionException e, List<String> mergePkColumns) {
    String translated =
        mergeTranslation(PipelineSqlErrorMessages.pgErrorOf(e), e.getMessage(), mergePkColumns);
    if (translated != null) {
      return new ScriptExecutionException(translated, e);
    }
    return e;
  }

  /**
   * 두 실패 번역이 공유하는 MERGE 번역 시도. MERGE 가 아니면(병합 키 null) 시도하지 않고 null — 해당 번역이 없을 때도 null 이라, 호출자가 공통
   * 문구(실행기)나 원래 예외(로컬)로 넘어간다.
   */
  private static String mergeTranslation(
      ExecutorClient.PgError pgError, String error, List<String> mergePkColumns) {
    return mergePkColumns != null
        ? PipelineSqlErrorMessages.describeMergeError(pgError, error, mergePkColumns)
        : null;
  }

  /** PG 후보 함수(V123 DB 함수)로 후보를 잡는다 — 시각 먼저 → 활성 트랜잭션 최소값 계산은 DB 함수가 책임진다. */
  @Override
  public OffsetDateTime captureIncrementalCandidate() {
    return incrementalCursorService.captureCandidate();
  }

  /** PG 판 비우기 문장 — 예전 형식 그대로다(실행기 선행문 화이트리스트가 이 형식을 받는다). */
  @Override
  public String outputClearStatement(String outputTableName) {
    return OutputClearStatement.deleteAll(outputTableName);
  }

  /** PG 는 보장할 행 트리거가 없다(_updated_at 트리거는 마이그레이션이 이미 깔았다) — 아무것도 하지 않는다. */
  @Override
  public void prepareSqlExecution(
      String sql, boolean isSelect, boolean incrementalStep, String outputTableName) {}

  /** PG APPEND 는 예전처럼 출력에 직접 적재한다 — 스테이징을 거치지 않는다. */
  @Override
  public boolean stagesAppend(boolean isReplace, String outputTable) {
    return false;
  }

  /** PG 는 스크립트 토큰이 없다(요청 본문에 키 자체가 없어 PG 동작이 불변이다). */
  @Override
  public String issueScriptToken(long tenantId, int readSlot, Duration ttl) {
    return null;
  }

  /** PG 자식 프로세스는 읽기 슬롯 롤로 띄운다(토큰은 쓰지 않는다). */
  @Override
  public PythonScriptExecutor.RunResult runLocalPython(
      String script, int readSlot, String scriptToken) {
    return pythonExecutor.run(script, readSlot);
  }

  /** PG 는 stdout JSON 을 앱 연결의 트랜잭션으로 적재한다. */
  @Override
  public long loadLocalPythonOutput(
      String targetTable, String stdout, Map<String, String> columnTypes) {
    return localPythonOutputLoader.load(targetTable, stdout, columnTypes);
  }

  /** PG REPLACE 만 출력 테이블 세션 잠금을 잡는다 — 같은 출력의 SQL 스텝 비우기+적재와 직렬화하려는 예전 동작(#735)을 바꾸지 않으려고 그대로 둔다. */
  @Override
  public boolean takesOutputSessionLock(boolean isReplace) {
    return isReplace;
  }
}
