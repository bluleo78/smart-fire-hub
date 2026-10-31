package com.smartfirehub.pipeline.service.step;

import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.dataset.storage.TenantDataStoreRepository;
import com.smartfirehub.dataset.storage.tibero.TiberoNames;
import com.smartfirehub.dataset.storage.tibero.TiberoPipelineSql;
import com.smartfirehub.dataset.storage.tibero.TiberoSqlLexer;
import com.smartfirehub.pipeline.exception.PipelineExecutionCancelledException;
import com.smartfirehub.pipeline.exception.ScriptExecutionException;
import com.smartfirehub.pipeline.service.ColumnInfo;
import com.smartfirehub.pipeline.service.LocalPythonOutputLoader;
import com.smartfirehub.pipeline.service.PipelineDataSchema;
import com.smartfirehub.pipeline.service.PipelineSqlErrorMessages;
import com.smartfirehub.pipeline.service.PythonScriptExecutor;
import com.smartfirehub.pipeline.service.TiberoIncrementalCursor;
import com.smartfirehub.pipeline.service.TiberoProbeTypes;
import com.smartfirehub.pipeline.service.executor.DbExecutorClient;
import com.smartfirehub.pipeline.service.executor.ExecutionCancelCheck;
import com.smartfirehub.pipeline.service.executor.ExecutorClient;
import com.smartfirehub.pipeline.service.executor.ScriptTokenIssuer;
import com.smartfirehub.pipeline.service.validator.SqlValidator;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Tibero 테넌트의 스텝 실행 방언(WD-231). 러너가 예전에 {@code tiberoTenant} 불리언으로 가르던 Tibero 쪽 분기 본문(행 트리거 보장·스크립트
 * 토큰 발급·열 탐침·SQL 실행 본체 등)을 그대로 옮겼다 — 동작 변화 없음.
 *
 * <p>Tibero 전용 열 탐침({@link #probeTibero}·{@link #tiberoResultColumns})은 인터페이스에 없는 공개 메서드다. PG
 * 탐침(SqlColumnProbe)은 보안 불변식 때문에 러너 {@code executeStep} 에 남으므로 탐침을 인터페이스 메서드로 올리지 않았고, 러너가 방언
 * 타입({@code instanceof TiberoStepDialect})으로 골라 부른다.
 *
 * <p>SQL 실행 본체({@link #executeSql}, 예전 러너의 {@code executeTiberoSqlStep}·열 맞춤·db-executor 호출)도 같은
 * 이유로 공개 메서드다 — PG 쪽은 싱크를 러너가 직접 불러야 해서 같은 모양의 인터페이스 메서드를 둘 수 없다.
 */
@Slf4j
public class TiberoStepDialect implements StepDialect {

  /** Tibero 결과 컬럼이 따옴표 소문자·대소문자 섞인 이름이라 적재 대상으로 쓸 수 없을 때의 안내(이름은 뒤에 붙인다). */
  public static final String QUOTED_RESULT_COLUMN_PREFIX = "Tibero 결과 컬럼 이름은 따옴표 없는 별칭으로 써 주세요(";

  private final PipelineDataSchema pipelineDataSchema;
  private final DataTableService dataTableService;
  private final TiberoIncrementalCursor tiberoIncrementalCursor;
  private final DbExecutorClient dbExecutorClient;
  private final ScriptTokenIssuer scriptTokenIssuer;
  private final TenantDataStoreRepository tenantDataStoreRepository;
  private final PythonScriptExecutor pythonExecutor;
  private final LocalPythonOutputLoader localPythonOutputLoader;
  private final DatasetColumnRepository columnRepository;

  public TiberoStepDialect(
      PipelineDataSchema pipelineDataSchema,
      DataTableService dataTableService,
      TiberoIncrementalCursor tiberoIncrementalCursor,
      DbExecutorClient dbExecutorClient,
      ScriptTokenIssuer scriptTokenIssuer,
      TenantDataStoreRepository tenantDataStoreRepository,
      PythonScriptExecutor pythonExecutor,
      LocalPythonOutputLoader localPythonOutputLoader,
      DatasetColumnRepository columnRepository) {
    this.pipelineDataSchema = pipelineDataSchema;
    this.dataTableService = dataTableService;
    this.tiberoIncrementalCursor = tiberoIncrementalCursor;
    this.dbExecutorClient = dbExecutorClient;
    this.scriptTokenIssuer = scriptTokenIssuer;
    this.tenantDataStoreRepository = tenantDataStoreRepository;
    this.pythonExecutor = pythonExecutor;
    this.localPythonOutputLoader = localPythonOutputLoader;
    this.columnRepository = columnRepository;
  }

  /**
   * Tibero 테넌트 SQL 스텝을 db-executor 로 실행한다(WD-77, 스펙 §5.1·§5.2). 호출 전에 검증·보안 게이트·출력 등급 처리·행 트리거 보장이
   * 끝나 있어야 한다. 컬럼 탐지·실행 모두 db-executor 로만 하고, 실행기 켬/끔과 무관하며 PG 실행기로 폴백하지 않는다(스펙 §3). db-executor
   * 호출은 {@code SqlGateArchitectureTest} 의 PG 싱크가 아니라 방언이 맡아도 된다.
   *
   * <p>SELECT 적재는 PG 경로와 같은 순서다: 결과 컬럼 탐지(probe) → 출력 데이터셋 컬럼과 맞춤 → 적재 문장 생성({@link
   * TiberoPipelineSql}) → 실행. 사용자 DML 은 문장을 그대로 보낸다. 두 경우 모두 REPLACE 비우기 선행문을 같은 요청(같은 트랜잭션)으로 보낸다.
   *
   * <p>실행기가 돌려준 실패(HTTP 200 + success=false)는 Tibero 오류 코드로 번역한 문구의 {@link
   * ScriptExecutionException} 이 된다. 실행기에 닿지 못한 경우는 {@link DbExecutorClient} 가 이미 연결 실패 문구로 던진다.
   *
   * @param run 실행 입력 — {@code tempDatasetAutoCreated} 면 TEMP 컬럼이 예약어 별칭({@code id} → {@code
   *     id_1})으로 만들어졌으므로 적재 대상 이름에도 같은 별칭을 쓴다(원본 열은 탐지된 이름 그대로 읽는다). {@code probeReuse} 는 TEMP 생성 때
   *     이미 탐지한 결과(없으면 여기서 탐지한다)
   * @param cancel 협조적 취소 확인(WD-153) — 실행 요청 직전에 확인한다
   * @return 실행 로그
   */
  public String executeSql(SqlStepRun run, ExecutionCancelCheck cancel) {
    String sql = run.sql();
    if (!run.isSelect()) {
      // 사용자 DML — 문장을 그대로 보낸다(REPLACE 면 비우기 선행문이 같은 트랜잭션으로 함께 간다).
      return runOnDbExecutor(sql, run.preStatements(), null, cancel);
    }
    String outputTableName = run.outputTableName();
    if (outputTableName == null) {
      // 출력 데이터셋 테이블 이름을 찾지 못했다(삭제 중 등). PG 경로는 이때 SELECT 를 그대로 실행하지만 결과를 쓸 곳이 없다 — Tibero 는
      // 실행하지 않고 실패시킨다.
      throw new ScriptExecutionException("출력 데이터셋의 테이블을 찾을 수 없습니다.");
    }

    // 결과 컬럼 탐지 — 실행기가 WHERE 1 = 0 으로 감싸 행 없이 실행한다. 실패 문구 접두는 PG 와 같은
    // PROBE_PREFIX 다(#682 규칙이 이 접두를 본다). TEMP 생성에서 이미 탐지했으면 재사용한다.
    DbExecutorClient.ProbeResult probe =
        run.probeReuse() != null ? run.probeReuse() : probeTibero(sql);
    List<DatasetColumnResponse> outputColumns =
        columnRepository.findByDatasetId(run.outputDatasetId());
    TiberoColumnMatch match =
        matchTiberoColumns(probe.columns(), outputColumns, run.tempDatasetAutoCreated());
    List<String> matchedColumns = match.targets();

    String schema = pipelineDataSchema.current();
    if (!run.isMerge()) {
      return runOnDbExecutor(
          TiberoPipelineSql.insertSelect(
              schema, outputTableName, matchedColumns, match.sources(), sql),
          run.preStatements(),
          null,
          cancel);
    }
    // PG 경로와 같이 출력 데이터셋의 기존 PK 를 병합 키로 쓴다 — 저장 이후 스키마가 바뀌었을 수 있어 실행 시점에 다시 확인한다.
    List<String> mergePkColumns = SqlOutputColumns.requireMergePk(outputColumns);
    return runOnDbExecutor(
        TiberoPipelineSql.mergeSelect(schema, outputTableName, matchedColumns, mergePkColumns, sql),
        run.preStatements(),
        mergePkColumns,
        cancel);
  }

  /**
   * Tibero 결과 컬럼을 출력 데이터셋 컬럼과 맞춘 결과 — 같은 위치의 {@code targets}(메타데이터 이름)와 {@code sources}(탐지된 원래 이름,
   * 적재 문장이 {@code src."<이름>"} 으로 읽는다).
   */
  private record TiberoColumnMatch(List<String> targets, List<String> sources) {}

  /**
   * Tibero 결과 컬럼을 출력 데이터셋 컬럼과 맞춘다 — PG 경로와 같은 규칙(데이터셋 컬럼을 하나도 빼지 않고, 결과에 있는 것만 대상). 시스템
   * 컬럼(ID·_UPDATED_AT·IMPORT_ID)은 데이터셋 사용자 컬럼이 아니므로 여기서 자연히 빠지고, ID·_UPDATED_AT 은 적재 문장이
   * 시퀀스·SYSTIMESTAMP 로 채운다.
   *
   * @param probed 실행기가 탐지한 결과 컬럼(이름은 바꾸지 않고 온다 — 따옴표 없는 별칭은 Tibero 가 대문자로 접어 돌려준다)
   * @param renameReserved 러너 자동 생성 TEMP 면 TEMP 만들 때와 같은 예약어 별칭을 대상 이름에 적용한다(PG 의 {@code
   *     renameReservedColumnNames} 와 같다). 탐지 컬럼 이름이 하나라도 쓸 수 없는 형태면 TEMP 생성 단계에서 이미 실패했으므로 목록 길이가
   *     같다
   * @return 적재 대상 컬럼(메타데이터 이름)과 그 원본 열 이름
   */
  private static TiberoColumnMatch matchTiberoColumns(
      List<DbExecutorClient.ProbeColumn> probed,
      List<DatasetColumnResponse> outputColumns,
      boolean renameReserved) {
    List<String> probedNames = probed.stream().map(DbExecutorClient.ProbeColumn::name).toList();
    Set<String> outputColumnNames = SqlOutputColumns.outputColumnNames(outputColumns);
    List<String> selectColumns = new ArrayList<>();
    List<String> sourceNames = new ArrayList<>();
    for (String name : probedNames) {
      String normalized = normalizeTiberoResultColumn(name);
      if (normalized != null) {
        selectColumns.add(normalized);
        sourceNames.add(name);
      } else if (name != null && outputColumnNames.contains(name.toLowerCase(Locale.ROOT))) {
        // 따옴표 소문자·대소문자 섞인 별칭(AS "amount", AS "Amount")은 출력 컬럼을 노린 이름이지만 Tibero 에서는 쓴 그대로라,
        // 적재 문장의 src."AMOUNT" 참조와 맞지 않는다. 조용히 빼면 그 컬럼이 비어(NULL) 적재되므로 맞춤 계산보다 먼저 안내하고 실패시킨다.
        throw new ScriptExecutionException(QUOTED_RESULT_COLUMN_PREFIX + name + ").");
      }
    }
    List<String> targetNames =
        renameReserved ? SqlOutputColumns.renameReservedColumnNames(selectColumns) : selectColumns;
    // 실패 안내에는 사용자가 쓴 그대로의(탐지된) 이름을 보여 준다.
    List<String> matched =
        SqlOutputColumns.matchOutputColumns(targetNames, probedNames, outputColumnNames);
    List<String> matchedSources = new ArrayList<>();
    for (int i = 0; i < targetNames.size(); i++) {
      if (outputColumnNames.contains(targetNames.get(i))) {
        matchedSources.add(sourceNames.get(i));
      }
    }
    return new TiberoColumnMatch(matched, matchedSources);
  }

  /**
   * db-executor 로 문장을 실행하고 실패 응답을 한국어 문구로 바꾼다.
   *
   * @param mergePkColumns MERGE 적재면 출력 PK(원본 키 중복 안내용), 아니면 null
   * @param cancel 협조적 취소 확인(WD-153)
   */
  private String runOnDbExecutor(
      String query,
      List<String> preStatements,
      List<String> mergePkColumns,
      ExecutionCancelCheck cancel) {
    // 취소 체크포인트(WD-153) — PG 경로와 같이 실행 직전. 아직 출력에 아무것도 쓰지 않았다(REPLACE 비우기도 이 요청과 같은 트랜잭션이다).
    cancel.checkpoint(0, false);
    // 실행 id 를 넘겨 db-executor 가 출력 잠금(LOCK TABLE)을 얻은 뒤 사용자 SQL 전에 취소를 되묻게 한다(WD-482 — 잠금 대기 중 취소).
    ExecutorClient.SqlExecuteResult result =
        dbExecutorClient.executeSql(query, preStatements, cancel.executionId());
    // db-executor 가 잠금 뒤 취소를 확인해 롤백했다(WD-482) — 실패가 아니라 취소다. 출력은 손대지 않았다. 취소 응답은 success=false 로
    // 오므로 실패 번역보다 먼저 본다.
    if (result.cancelled()) {
      throw new PipelineExecutionCancelledException(0, false);
    }
    if (!result.success()) {
      String translated =
          mergePkColumns != null
              ? PipelineSqlErrorMessages.describeTiberoMergeError(
                  result.dbError(), result.error(), mergePkColumns)
              : null;
      throw new ScriptExecutionException(
          translated != null
              ? translated
              : PipelineSqlErrorMessages.describeTiberoError(
                  PipelineSqlErrorMessages.EXECUTION_PREFIX, result.dbError(), result.error()));
    }
    return result.executionLog();
  }

  /**
   * Tibero 테넌트는 _UPDATED_AT 이 Tibero 서버 시계(SYSTIMESTAMP)의 벽시계라, 후보도 Tibero 시계와 Tibero 의 열린 트랜잭션에서
   * 잡는다(PG 시계와 섞지 않는다). 후보를 SQL 실행 전에 잡는다는 불변식은 두 방언이 같다. 책갈피 저장(advanceCursor)은 PG timestamptz
   * 그대로이고 값만 Tibero 시계다.
   */
  @Override
  public OffsetDateTime captureIncrementalCandidate() {
    return tiberoIncrementalCursor.captureCandidate();
  }

  /**
   * Tibero 판 비우기 문장 — db-executor 선행문 규칙({@code PreStatementRule})이 받는 Tibero 형식만 보내야 하고(PG 형식은
   * 거부된다) 대상 테이블을 먼저 잠근 뒤 같은 트랜잭션에서 실행한다(#731/#735 와 같은 직렬화·원자성). REPLACE 비우기와 증분 전체 재구축이 같은 문장을
   * 쓴다.
   */
  @Override
  public String outputClearStatement(String outputTableName) {
    return TiberoPipelineSql.deleteAll(pipelineDataSchema.current(), outputTableName);
  }

  /**
   * 사용자 DML 이 기대는 행 트리거를 실행 직전에 보장하고(비SELECT), 증분 스텝이면 출력·입력 테이블 트리거도 보장한다. 호출 순서(보안 게이트·출력 등급 처리
   * 뒤)는 호출자가 지킨다 — 거부된 SQL 은 DDL 을 만들지 않는다.
   */
  @Override
  public void prepareSqlExecution(
      String sql, boolean isSelect, boolean incrementalStep, String outputTableName) {
    if (!isSelect) {
      // 사용자 DML 이 기대는 행 트리거를 실행 직전에 보장한다 — 보안 게이트·출력 등급 처리가 끝난 뒤라 거부된 SQL 은 DDL 을 만들지 않는다.
      ensureTiberoRowTriggers(sql);
    }
    if (incrementalStep) {
      // 증분은 _UPDATED_AT 이 UPDATE 때도 움직여야 맞다 — 출력·입력 테이블 트리거를 실행 전에 보장하고, 못 하면 실행하지 않는다.
      ensureTiberoIncrementalTriggers(sql, outputTableName);
    }
  }

  /**
   * Tibero 테넌트의 APPEND 적재가 스테이징을 거치는가(WD-77·WD-78, 스펙 §3.3) — PYTHON(실행기 켬/끔)·API_CALL 세 경로가 같은 조건을
   * 쓴다.
   *
   * <p>실행기 적재는 db-executor 요청 하나가 Tibero 트랜잭션 하나라, 여러 요청(페이지·청크)을 출력에 직접 넣으면 중간 실패가 일부 행을 남긴다. 그래서
   * Tibero 는 APPEND 도 스테이징에 모은 뒤 옮기기 한 번으로 전부 반영 또는 전부 취소한다. REPLACE 는 원래 스테이징을 거치므로 여기서 세지 않고, 출력
   * 테이블이 없으면 적재할 것이 없다.
   *
   * @param isReplace 경로별로 해석한 REPLACE 여부(경로마다 해석 규칙이 달라 호출자가 넘긴다)
   */
  @Override
  public boolean stagesAppend(boolean isReplace, String outputTable) {
    return !isReplace && outputTable != null;
  }

  /**
   * Tibero PYTHON 의 스크립트 토큰을 발급한다(WD-78). 세대({@code script_token_epoch})는 {@code prepareForRun} 뒤에
   * 읽어야 한다 — 호출부 참조. 세대가 없거나(테넌트 저장소 행 없음) 발급하지 못하면(서명 키 미설정 등) 한국어 문구로 단계를 실패시킨다(fail-closed). 토큰은
   * 로그하지 않는다.
   */
  @Override
  public String issueScriptToken(long tenantId, int readSlot, Duration ttl) {
    long epoch =
        tenantDataStoreRepository
            .findScriptTokenEpoch(tenantId)
            .orElseThrow(
                () ->
                    new ScriptExecutionException(
                        "Tibero 저장소 정보에서 스크립트 토큰 세대를 찾지 못해 Python 단계를 실행할 수 없습니다."));
    try {
      return scriptTokenIssuer.issue(tenantId, readSlot, epoch, ttl);
    } catch (IllegalStateException | IllegalArgumentException e) {
      // 발급기 문구는 설정 키·환경 변수 이름을 담는다 — 스텝 오류(사용자 화면)에는 일반 안내만 싣고 자세한 사유는 서버 WARN 로그로 남긴다(코드리뷰 3).
      // 발급기 문구에는 키·토큰 값이 없어 로그에 남겨도 된다.
      log.warn("Tibero PYTHON 스크립트 토큰 발급 실패: tenantId={} — {}", tenantId, e.getMessage());
      throw new ScriptExecutionException(PythonScriptExecutor.TIBERO_SETUP_MISSING, e);
    }
  }

  /** Tibero 자식은 PG 슬롯 롤이 아니라 조회 도우미 환경(db-executor 주소·스크립트 토큰)으로 띄운다. */
  @Override
  public PythonScriptExecutor.RunResult runLocalPython(
      String script, int readSlot, String scriptToken) {
    return pythonExecutor.runTibero(script, scriptToken);
  }

  /** Tibero 대상은 언제나 스테이징이다 — PG 트랜잭션 없이 스테이징 창구로 넣는다. */
  @Override
  public long loadLocalPythonOutput(
      String targetTable, String stdout, Map<String, String> columnTypes) {
    return localPythonOutputLoader.loadStaging(targetTable, stdout, columnTypes);
  }

  /**
   * Tibero 는 출력 세션 잠금을 잡지 않는다 — 잠금의 목적(같은 출력의 PG SQL 비우기+적재와의 직렬화)이 Tibero 에는 없고(Tibero SQL 단계는 이
   * 키를 잡지 않는다), 잡으면 Tibero 작업 내내 PG 풀 연결만 쥔다. 원자성은 스테이징 + 옮기기(한 Tibero 트랜잭션)가 맡는다.
   */
  @Override
  public boolean takesOutputSessionLock(boolean isReplace) {
    return false;
  }

  /**
   * Tibero 증분 스텝의 출력 테이블과 읽는(입력) 테이블 전부에 행 트리거({@code _UPDATED_AT} 갱신)를 보장한다(WD-77 Task 10).
   *
   * <p>트리거가 없으면 UPDATE 로 바뀐 행의 {@code _UPDATED_AT} 이 움직이지 않아 증분이 그 변경을 영영 못 읽는다 — 새로 여는 기능이라 사용자
   * UPDATE 와 달리 <b>fail-closed</b> 다: 권한이 없으면 Task 8 의 운영자 안내({@link
   * com.smartfirehub.dataset.storage.DataStoreFeatureUnsupportedException})로, 그 밖의 오류도 그대로 올려 실행 전에
   * 단계를 실패시킨다. 입력 테이블은 검증기가 AST 로 뽑은 읽기 대상(테넌트 스키마·CTE·DUAL 제외)이고, 출력과 같은 테이블이 입력에도 있으면 한 번만 보장한다.
   * 이름은 물리(대문자) 이름으로 맞춰 중복을 접는다.
   */
  private void ensureTiberoIncrementalTriggers(String sql, String outputTableName) {
    Set<String> tables = new LinkedHashSet<>();
    if (outputTableName != null) {
      tables.add(TiberoNames.physical(outputTableName));
    }
    for (SqlValidator.TableName read :
        pipelineDataSchema.validator().referencedTables(sql).reads()) {
      tables.add(TiberoNames.physical(read.name()));
    }
    // 상태 확인·생성을 한 접속에서 묶는다. 하나라도 못 보장하면 예외로 실행 전에 멈춘다(fail-closed).
    dataTableService.ensureRowTriggers(List.copyOf(tables));
  }

  /**
   * Tibero 사용자 DML 의 쓰기 대상 테이블에 행 트리거(ID·_UPDATED_AT)를 보장한다(WD-77).
   *
   * <p>INSERT 는 트리거가 {@code ID} 를 채우므로 필수다 — 권한이 없으면 Task 8 의 운영자 안내(422)로 실행 전에 실패하고 조용히 건너뛰지 않는다.
   * UPDATE 는 트리거가 {@code _UPDATED_AT} 만 갱신하고 이 단계 이전에도 허용되던 문장이라, 권한이 없다고 새로 막지 않고 경고만 남긴 채
   * 진행한다(운영자가 권한을 줄 때까지 갱신 시각이 움직이지 않는다). DELETE 는 트리거가 필요 없다. 쓰기 대상은 검증기가 AST 로 뽑으며 테넌트 스키마 밖 대상은
   * 앞선 검증이 이미 거부했다. 검증을 통과한 SQL 이라 따옴표·주석이 닫혀 있어 어휘 분석기가 예외를 던지지 않는다.
   */
  private void ensureTiberoRowTriggers(String sql) {
    String keyword = TiberoSqlLexer.firstKeyword(sql);
    boolean insert = "INSERT".equals(keyword);
    boolean update = "UPDATE".equals(keyword);
    if (!insert && !update) {
      return;
    }
    List<String> targets =
        pipelineDataSchema.validator().referencedTables(sql).writes().stream()
            .map(SqlValidator.TableName::name)
            .toList();
    if (targets.isEmpty()) {
      return;
    }
    if (insert) {
      dataTableService.ensureRowTriggers(targets);
      return;
    }
    try {
      dataTableService.ensureRowTriggers(targets);
    } catch (RuntimeException e) {
      // 권한 부족뿐 아니라 접속·DDL 오류도 UPDATE 를 막지 않는다(이전부터 허용되던 문장) — 원인은 스택 없이 클래스·메시지만 남긴다.
      // UPDATE 의 쓰기 대상은 한 테이블이라 테이블마다 따로 보장하던 때와 결과가 같다.
      log.warn(
          "Tibero 행 트리거를 보장하지 못해 트리거 없이 UPDATE 를 실행합니다(_UPDATED_AT 이 갱신되지 않음): {} — {}: {}",
          targets,
          e.getClass().getSimpleName(),
          e.getMessage());
    }
  }

  /**
   * Tibero 결과 컬럼을 db-executor 로 탐지한다. 실패하면 PG 와 같은 접두({@link
   * PipelineSqlErrorMessages#PROBE_PREFIX})의 번역 문구로 단계를 실패시킨다.
   */
  public DbExecutorClient.ProbeResult probeTibero(String sql) {
    DbExecutorClient.ProbeResult probe = dbExecutorClient.probe(sql);
    if (!probe.success()) {
      throw new ScriptExecutionException(
          PipelineSqlErrorMessages.describeTiberoError(
              PipelineSqlErrorMessages.PROBE_PREFIX, probe.dbError(), probe.error()));
    }
    return probe;
  }

  /**
   * Tibero probe 결과를 임시 데이터셋 컬럼(메타데이터 이름·앱 타입)으로 바꾼다 — 이름은 {@link #normalizeTiberoResultColumn},
   * 타입은 {@link TiberoProbeTypes}. 따옴표 소문자·대소문자 섞인 별칭은 적재 문장이 읽을 수 없으므로 TEMP 를 만들기 전에 안내하고 실패시킨다.
   */
  public List<ColumnInfo> tiberoResultColumns(DbExecutorClient.ProbeResult probe) {
    List<ColumnInfo> columns = new ArrayList<>();
    for (DbExecutorClient.ProbeColumn c : probe.columns()) {
      String normalized = normalizeTiberoResultColumn(c.name());
      if (normalized == null) {
        throw new ScriptExecutionException(QUOTED_RESULT_COLUMN_PREFIX + c.name() + ").");
      }
      columns.add(new ColumnInfo(normalized, TiberoProbeTypes.toAppType(c)));
    }
    return columns;
  }

  /**
   * Tibero 결과 컬럼 이름(실행기가 바꾸지 않고 보낸 원래 이름)을 메타데이터 이름으로 바꾼다. 맞춤에 쓸 수 없으면 null.
   *
   * <ul>
   *   <li>전부 대문자 — Tibero 가 따옴표 없는 별칭을 대문자로 접은 것이다. 소문자로 바꿔 메타데이터(소문자) 컬럼과 맞춘다. 적재 문장의 {@code
   *       src."AMOUNT"} 참조가 이 물리 이름과 같다.
   *   <li>그 밖(따옴표 소문자 {@code "amount"}·대소문자 섞임 {@code "Mixed"}) — 사용자가 따옴표로 지은 다른 이름이라 맞춤에 쓰지 않는다.
   *       쓰면 적재 문장이 대문자 물리 이름을 참조해 "알 수 없는 식별자"로 실패한다. 실행기가 예전처럼 소문자로 바꿔 보내면 {@code AS "amount"} 와
   *       {@code AS amount} 를 가를 수 없어서, 실행기는 원래 이름을 보내고 여기서만 정규화한다.
   * </ul>
   */
  public static String normalizeTiberoResultColumn(String name) {
    if (name == null || !name.equals(name.toUpperCase(Locale.ROOT))) {
      return null;
    }
    return name.toLowerCase(Locale.ROOT);
  }
}
